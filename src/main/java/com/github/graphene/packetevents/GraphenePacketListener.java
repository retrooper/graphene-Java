package com.github.graphene.packetevents;

import com.github.graphene.Graphene;
import com.github.graphene.handler.PacketDecryptionHandler;
import com.github.graphene.handler.PacketEncryptionHandler;
import com.github.graphene.user.User;
import com.github.graphene.util.UUIDUtil;
import com.github.graphene.wrapper.play.server.WrapperPlayServerJoinGame;
import com.github.graphene.wrapper.play.server.WrapperStatusServerResponse;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListener;
import com.github.retrooper.packetevents.event.impl.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.impl.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.chat.component.serializer.ComponentSerializer;
import com.github.retrooper.packetevents.protocol.gameprofile.GameProfile;
import com.github.retrooper.packetevents.protocol.gameprofile.TextureProperty;
import com.github.retrooper.packetevents.protocol.nbt.*;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.util.MinecraftEncryptionUtil;
import com.github.retrooper.packetevents.wrapper.login.client.WrapperLoginClientEncryptionResponse;
import com.github.retrooper.packetevents.wrapper.login.client.WrapperLoginClientLoginStart;
import com.github.retrooper.packetevents.wrapper.login.server.WrapperLoginServerEncryptionRequest;
import com.github.retrooper.packetevents.wrapper.login.server.WrapperLoginServerLoginSuccess;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientSettings;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerHeldItemChange;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerPositionAndLook;
import com.github.retrooper.packetevents.wrapper.status.client.WrapperStatusClientPing;
import com.github.retrooper.packetevents.wrapper.status.server.WrapperStatusServerPong;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.netty.channel.ChannelPipeline;

import javax.crypto.Cipher;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.SecretKey;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigInteger;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

public class GraphenePacketListener implements PacketListener {
    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        User user = (User) event.getPlayer();
        assert user != null;
        switch (event.getConnectionState()) {
            case HANDSHAKING:
                break;
            case STATUS:
                if (event.getPacketType() == PacketType.Status.Client.REQUEST) {
                    JsonObject responseComponent = new JsonObject();

                    JsonObject versionComponent = new JsonObject();
                    versionComponent.addProperty("name", Graphene.SERVER_VERSION_NAME);
                    versionComponent.addProperty("protocol", Graphene.SERVER_PROTOCOL_VERSION);
                    //Add sub component
                    responseComponent.add("version", versionComponent);

                    JsonObject playersComponent = new JsonObject();
                    playersComponent.addProperty("max", Graphene.MAX_PLAYERS);
                    playersComponent.addProperty("online", Graphene.ONLINE_PLAYERS);
                    //Add sub component
                    responseComponent.add("players", playersComponent);

                    JsonObject descriptionComponent = new JsonObject();
                    descriptionComponent.addProperty("text", Graphene.SERVER_DESCRIPTION);
                    //Add sub component
                    responseComponent.add("description", descriptionComponent);

                    WrapperStatusServerResponse response = new WrapperStatusServerResponse(responseComponent);
                    PacketEvents.getAPI().getPlayerManager().sendPacket(event.getChannel(), response);
                } else if (event.getPacketType() == PacketType.Status.Client.PING) {
                    WrapperStatusClientPing ping = new WrapperStatusClientPing(event);
                    long time = ping.getTime();

                    WrapperStatusServerPong pong = new WrapperStatusServerPong(time);
                    PacketEvents.getAPI().getPlayerManager().sendPacket(event.getChannel(), pong);
                    user.forceDisconnect();
                }
                break;
            case LOGIN:
                if (event.getPacketType() == PacketType.Login.Client.LOGIN_START) {
                    WrapperLoginClientLoginStart start = new WrapperLoginClientLoginStart(event);

                    //Map the player usernames with their netty channels
                    PacketEvents.getAPI().getPlayerManager().CHANNELS.put(start.getUsername(), event.getChannel());
                    user.setUsername(start.getUsername());
                    UUID uuid = UUID.nameUUIDFromBytes(("OfflinePlayer:" + user.getUsername()).getBytes(StandardCharsets.UTF_8));
                    user.setUUID(uuid);

                    //Encryption begins here
                    String serverID = "";
                    PublicKey key = Graphene.KEY_PAIR.getPublic();
                    byte[] verifyToken = new byte[4];
                    new Random().nextBytes(verifyToken);

                    user.setVerifyToken(verifyToken);
                    user.setServerId(serverID);

                    WrapperLoginServerEncryptionRequest encryptionRequest = new WrapperLoginServerEncryptionRequest(serverID, key, verifyToken);
                    PacketEvents.getAPI().getPlayerManager().sendPacket(event.getChannel(), encryptionRequest);
                    Graphene.LOGGER.info("Sent encryption request to " + user.getUsername());
                } else if (event.getPacketType() == PacketType.Login.Client.ENCRYPTION_RESPONSE) {
                    WrapperLoginClientEncryptionResponse encryptionResponse = new WrapperLoginClientEncryptionResponse(event);
                    user.setGameProfile(new GameProfile(user.getUUID(), user.getUsername()));

                    Graphene.LOGGER.info("Calling thread-pool for authenticating user " + user.getUsername() + "!");

                    // Authenticate and handle player connection on a separate
                    // ExecutorService pool of threads.
                    Graphene.WORKER_THREADS.execute(() -> {
                        byte[] verifyToken = MinecraftEncryptionUtil.decryptRSA(Graphene.KEY_PAIR.getPrivate(), encryptionResponse.getEncryptedVerifyToken());
                        PrivateKey privateKey = Graphene.KEY_PAIR.getPrivate();
                        byte[] sharedSecret = MinecraftEncryptionUtil.decrypt(privateKey.getAlgorithm(), privateKey, encryptionResponse.getEncryptedSharedSecret());
                        MessageDigest digest;
                        try {
                            digest = MessageDigest.getInstance("SHA-1");
                        } catch (NoSuchAlgorithmException e) {
                            e.printStackTrace();
                            user.forceDisconnect();
                            return; // basically asserts that digest must be not null
                        }

                        digest.update(user.getServerId().getBytes(StandardCharsets.UTF_8));
                        digest.update(sharedSecret);
                        digest.update(Graphene.KEY_PAIR.getPublic().getEncoded());
                        String serverIdHash = new BigInteger(digest.digest()).toString(16);

                        if (Arrays.equals(user.getVerifyToken(), verifyToken)) {
                            try {
                                String ip = user.getAddress().getHostName();
                                URL url = new URL("https://sessionserver.mojang.com/session/minecraft/hasJoined?username=" + user.getUsername() + "&serverId=" + serverIdHash);
                                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                                connection.setRequestProperty("Authorization", null);
                                connection.setRequestMethod("GET");
                                Graphene.LOGGER.info("Authenticating " + user.getUsername() + "...");
                                if (connection.getResponseCode() == 204) {
                                    Graphene.LOGGER.info("Failed to authenticate " + user.getUsername() + "!");
                                    user.kickLogin("Failed to authenticate your connection.");
                                    return;
                                }
                                BufferedReader in = new BufferedReader(
                                        new InputStreamReader(connection.getInputStream()));
                                String inputLine;
                                // We know the output from here MUST be a string (assuming
                                // - they don't change their API) so we can use StringBuilder not buffer.
                                StringBuilder sb = new StringBuilder();
                                while ((inputLine = in.readLine()) != null) {
                                    sb.append(inputLine);
                                }
                                in.close();
                                JsonObject jsonObject = ComponentSerializer.GSON.fromJson(sb.toString(), JsonObject.class);

                                String username = jsonObject.get("name").getAsString();
                                String rawUUID = jsonObject.get("id").getAsString();
                                UUID uuid = UUIDUtil.fromStringWithoutDashes(rawUUID);
                                JsonArray textureProperties = jsonObject.get("properties").getAsJsonArray();

                                GameProfile profile = user.getGameProfile();
                                for (JsonElement element : textureProperties) {
                                    JsonObject property = element.getAsJsonObject();

                                    String name = property.get("name").getAsString();
                                    String value = property.get("value").getAsString();
                                    String signature = property.get("signature").getAsString();

                                    profile.getTextureProperties().add(new TextureProperty(name, value, signature));
                                }
                                user.setGameProfile(profile);

                                user.setUUID(uuid);
                                user.setUsername(username);

                                ChannelPipeline pipeline = user.getChannel().pipeline();

                                SecretKey sharedSecretKey = new SecretKeySpec(sharedSecret, "AES");

                                Cipher decryptCipher = Cipher.getInstance("AES/CFB8/NoPadding");
                                decryptCipher.init(Cipher.DECRYPT_MODE, sharedSecretKey, new IvParameterSpec(sharedSecret));

                                pipeline.addBefore("packet_splitter", "decryption_handler", new PacketDecryptionHandler(decryptCipher));

                                Cipher encryptCipher = Cipher.getInstance("AES/CFB8/NoPadding");
                                encryptCipher.init(Cipher.ENCRYPT_MODE, sharedSecretKey, new IvParameterSpec(sharedSecret));
                                pipeline.addBefore("packet_prepender", "encryption_handler", new PacketEncryptionHandler(encryptCipher));
                                sendPostLoginPackets(event);
                            } catch (IOException | NoSuchPaddingException | NoSuchAlgorithmException
                                    | InvalidKeyException | InvalidAlgorithmParameterException ex) {
                                ex.printStackTrace();
                            }
                        } else {
                            Graphene.LOGGER.warning("Failed to authenticate " + user.getUsername() + ", because they replied with an invalid verify token!");
                            user.forceDisconnect();
                        }
                    });
                }

                break;
            case PLAY:
                if (event.getPacketType() == PacketType.Play.Client.CLIENT_SETTINGS) {
                    WrapperPlayClientSettings settings = new WrapperPlayClientSettings(event);
                    System.out.println("got settings, hand: " + settings.getMainHand());
                }
                break;
        }

    }

    @Override
    public void onPacketSend(PacketSendEvent event) {

    }

    public static void sendPostLoginPackets(PacketReceiveEvent event) {
        User user = (User) event.getPlayer();
        WrapperLoginServerLoginSuccess loginSuccess = new WrapperLoginServerLoginSuccess(user.getUUID(), user.getUsername());
        PacketEvents.getAPI().getPlayerManager().sendPacket(event.getChannel(), loginSuccess);
        Graphene.LOGGER.info(user.getUsername() + " has joined the server!");

        byte[] dimensionBytes = new byte[0];
        try (InputStream dimensionInfo = Graphene.class.getClassLoader().getResourceAsStream("RawDimensions.bytes")) {
            dimensionBytes = new byte[dimensionInfo.available()];
            dimensionInfo.read(dimensionBytes);
        }
        catch (IOException e) {
            e.printStackTrace();
        }


        byte[] dimensionCodec = new byte[0];
        try (InputStream dimensionCodecInfo = Graphene.class.getClassLoader().getResourceAsStream("RawCodec.bytes")) {
            dimensionCodec = new byte[dimensionCodecInfo.available()];
            dimensionCodecInfo.read(dimensionCodec);
        }
        catch (IOException e) {
            e.printStackTrace();
        }

        List<String> worldNames = new ArrayList<>();
        worldNames.add("minecraft:overworld");
        worldNames.add("minecraft:the_nether");
        worldNames.add("minecraft:the_end");
        long hashedSeed = 0L;

        System.out.println("dc: " + dimensionCodec.length + ", dimensions: " + dimensionBytes.length);

        WrapperPlayServerJoinGame joinGame = new WrapperPlayServerJoinGame(user.getEntityId(),
                false, user.getGameMode(), user.getPreviousGameMode(),
                worldNames, dimensionCodec, dimensionBytes, worldNames.get(0), hashedSeed, Graphene.MAX_PLAYERS, 10, 20,
                true, true, false, true);

        PacketEvents.getAPI().getPlayerManager().sendPacket(event.getChannel(), joinGame);
        System.out.println("send join game!");

        WrapperPlayServerHeldItemChange heldItemChange = new WrapperPlayServerHeldItemChange(0);
        PacketEvents.getAPI().getPlayerManager().sendPacket(event.getChannel(), heldItemChange);

        System.out.println("send held item change!");

        WrapperPlayServerPlayerPositionAndLook positionAndLook = new WrapperPlayServerPlayerPositionAndLook(0, 6, 0, 0.0f, 0.0f, 0, 0, true);
        PacketEvents.getAPI().getPlayerManager().sendPacket(event.getChannel(), positionAndLook);
        System.out.println("send position and look!");
    }
}