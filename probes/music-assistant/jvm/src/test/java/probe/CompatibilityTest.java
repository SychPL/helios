package probe;

import com.sendspin.protocol.*;
import com.squareup.moshi.Moshi;
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory;
import okhttp3.*;
import okhttp3.mockwebserver.*;
import org.json.JSONObject;
import org.junit.Test;
import java.net.InetAddress;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

public class CompatibilityTest {
    private Moshi moshi() {
        return new Moshi.Builder().add(new JsonOptionalAdapterFactory())
                .addLast(new KotlinJsonAdapterFactory()).build();
    }

    @Test public void legacyWebsocketHelloAndStateWithoutAudio() throws Exception {
        MockWebServer server = new MockWebServer();
        AtomicReference<String> hello = new AtomicReference<>();
        CountDownLatch receivedHello = new CountDownLatch(1);
        server.enqueue(new MockResponse().withWebSocketUpgrade(new WebSocketListener() {
            @Override public void onClosing(WebSocket socket, int code, String reason) {
                socket.close(code, reason);
            }
            @Override public void onMessage(WebSocket socket, String text) {
                if (new JSONObject(text).getString("type").equals("client/hello")) {
                    hello.set(text);
                    socket.send("{\"type\":\"server/hello\",\"payload\":{\"server_id\":\"local-fixture\",\"name\":\"Loopback\",\"version\":1,\"active_roles\":[\"player@v1\",\"metadata@v1\",\"artwork@v1\",\"controller@v1\"]}}");
                    socket.send("{\"type\":\"server/state\",\"payload\":{\"metadata\":{\"title\":\"Fixture track\"},\"controller\":{\"supported_commands\":[\"stop\",\"volume\"],\"volume\":23,\"muted\":false}}}");
                    receivedHello.countDown();
                }
            }
        }));
        server.start(InetAddress.getByName("127.0.0.1"), 0);
        OkHttpClient http = new OkHttpClient();
        ClientPreferences preferences = new ClientPreferences(
                Collections.singletonList(new com.sendspin.protocol.AudioFormat("pcm", 2, 48000, 16)),
                Collections.singletonList(new ArtworkChannel("album", "jpeg", 320, 320)),
                null, 262144, Arrays.asList("volume", "mute"),
                new HashSet<>(Arrays.asList(OptionalRole.PLAYER, OptionalRole.METADATA,
                        OptionalRole.ARTWORK, OptionalRole.CONTROLLER)));
        SendSpinClient client = new SendSpinClient(http, moshi(), preferences,
                "helios-probe-fixed-id", "Helios Probe", "Helios", "JVM fixture", "probe",
                null, (buffer, clock) -> NoOpAudioPlayer.INSTANCE, false, NoOpClientSettingsStore.INSTANCE);
        try {
            assertEquals(ClientState.IDLE, client.getState().getValue());
            client.connect(server.url("/sendspin").toString().replace("http://", "ws://"));
            assertTrue("hello timeout", receivedHello.await(5, TimeUnit.SECONDS));
            JSONObject payload = new JSONObject(hello.get()).getJSONObject("payload");
            assertEquals("helios-probe-fixed-id", payload.getString("client_id"));
            assertEquals(4, payload.getJSONArray("supported_roles").length());
            assertEquals("pcm", payload.getJSONObject("player@v1_support")
                    .getJSONArray("supported_formats").getJSONObject(0).getString("codec"));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (client.getState().getValue() != ClientState.STREAMING && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            assertNotNull("server hello not processed", client.getServerHello().getValue());
            assertEquals("Loopback", client.getServerName().getValue());
            assertEquals(ClientState.STREAMING, client.getState().getValue());
            assertNotNull(client.getControllerState().getValue());
            assertEquals(Integer.valueOf(23), client.getControllerState().getValue().getVolume());
            assertTrue(client.getControllerState().getValue().getSupportedCommands().contains("stop"));
            assertFalse(client.getServerState().getReplayCache().isEmpty());
            ServerState state = client.getServerState().getReplayCache().get(0);
            TrackMetadataMsg metadata = ((JsonOptional.Present<TrackMetadataMsg>) state.getMetadata()).getValue();
            assertEquals("Fixture track", ((JsonOptional.Present<String>) metadata.getTitle()).getValue());
            assertFalse(client.getAudioPlayer().isPlaying());
        } finally {
            client.disconnect("user_request");
            assertEquals(ClientState.DISCONNECTED, client.getState().getValue());
            server.shutdown();
            http.dispatcher().executorService().shutdownNow();
            http.connectionPool().evictAll();
        }
    }

    @Test public void currentCoreMessagesExposeCompatibilityGap() {
        MessageParser parser = new MessageParser(moshi());
        assertTrue(parser.parseText("{\"type\":\"server/init\",\"payload\":{}}") instanceof UnknownMessage);
        assertTrue(parser.parseText("{\"type\":\"server/activate\",\"payload\":{}}") instanceof UnknownMessage);
        assertTrue(parser.parseText("{\"type\":\"server/pair-auth\",\"payload\":{}}") instanceof UnknownMessage);
        assertNull(parser.parseText("{\"type\":\"server/hello\",\"payload\":{\"name\":\"Current core\"}}"));
    }
}
