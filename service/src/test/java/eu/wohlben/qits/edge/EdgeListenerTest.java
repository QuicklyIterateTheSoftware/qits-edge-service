package eu.wohlben.qits.edge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.ext.web.Router;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The listener route, on a plain Vert.x server holding exactly the handler {@link EdgeListener}
 * installs first and one route behind it standing for everything the edge does after.
 *
 * <p>The server listens on {@code 127.0.0.1}, which a configured list always admits, so the refusal
 * is proven with {@link ListenerAllowList#exactly} — the same check without the loopback added.
 */
class EdgeListenerTest {

  private Vertx vertx;

  @AfterEach
  void close() throws Exception {
    if (vertx != null) {
      vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }
  }

  @Test
  void aConnectionOnARefusedLocalAddressIsClosedBeforeRouting() throws Exception {
    List<String> routed = new CopyOnWriteArrayList<>();
    int port = serve(ListenerAllowList.exactly(List.of(Cidr.parse("100.96.0.0/24"))), null, routed);

    assertEquals("", exchange(port), "closed with nothing written back");
    assertEquals(List.of(), routed, "nothing behind the listener route ran");
  }

  @Test
  void aConnectionOnAnAllowedLocalAddressIsRouted() throws Exception {
    List<String> routed = new CopyOnWriteArrayList<>();
    int port = serve(ListenerAllowList.of(List.of("100.96.0.0/24")), null, routed);

    assertTrue(exchange(port).startsWith("HTTP/1.1 200"), "the loopback is always admitted");
    assertEquals(List.of("/peers"), routed);
  }

  @Test
  void aPrivateEdgeLogsOneLinePerRequestOnThePeerLogger() throws Exception {
    List<String> lines = new CopyOnWriteArrayList<>();
    java.util.logging.Logger logger = java.util.logging.Logger.getLogger(PeerLog.CATEGORY);
    Handler capture =
        new Handler() {
          @Override
          public void publish(LogRecord record) {
            lines.add(record.getMessage());
          }

          @Override
          public void flush() {}

          @Override
          public void close() {}
        };
    logger.addHandler(capture);
    try {
      int port =
          serve(
              ListenerAllowList.of(List.of()),
              new PeerLog(List.of(), Cidr.parse("10.231.0.0/20")),
              new CopyOnWriteArrayList<>());
      exchange(port);
      for (int i = 0; i < 50 && lines.isEmpty(); i++) {
        Thread.sleep(20);
      }
      Thread.sleep(100);
      assertEquals(
          List.of(
              "peer=unknown ip=127.0.0.1 method=GET host=ci.dev.acme.wohlben.eu path=/peers"
                  + " status=200"),
          lines);
    } finally {
      logger.removeHandler(capture);
    }
  }

  @Test
  void theLoopbackHealthCheckWritesNoPeerLine() throws Exception {
    List<String> lines = new CopyOnWriteArrayList<>();
    java.util.logging.Logger logger = java.util.logging.Logger.getLogger(PeerLog.CATEGORY);
    Handler capture =
        new Handler() {
          @Override
          public void publish(LogRecord record) {
            lines.add(record.getMessage());
          }

          @Override
          public void flush() {}

          @Override
          public void close() {}
        };
    logger.addHandler(capture);
    try {
      List<String> routed = new CopyOnWriteArrayList<>();
      int port =
          serve(
              ListenerAllowList.of(List.of()),
              new PeerLog(List.of(), Cidr.parse("10.231.0.0/20")),
              routed,
              false);
      assertTrue(exchange(port).startsWith("HTTP/1.1 200"), "the health check is still served");
      Thread.sleep(150);
      assertEquals(List.of(), lines);
    } finally {
      logger.removeHandler(capture);
    }
  }

  private int serve(ListenerAllowList allowed, PeerLog peerLog, List<String> routed)
      throws Exception {
    return serve(allowed, peerLog, routed, true);
  }

  private int serve(
      ListenerAllowList allowed, PeerLog peerLog, List<String> routed, boolean logLoopback)
      throws Exception {
    vertx = Vertx.vertx();
    Router router = Router.router(vertx);
    router
        .route()
        .order(EdgeListener.ROUTE_ORDER)
        .handler(EdgeListener.handler(allowed, peerLog, logLoopback));
    router
        .route()
        .handler(
            rc -> {
              routed.add(rc.request().path());
              rc.response().setStatusCode(200).end("routed\n");
            });
    HttpServer server =
        vertx
            .createHttpServer()
            .requestHandler(router)
            .listen(0, "127.0.0.1")
            .toCompletionStage()
            .toCompletableFuture()
            .get(10, TimeUnit.SECONDS);
    return server.actualPort();
  }

  /** One raw request, and everything that comes back before the server closes or 2 s pass. */
  private static String exchange(int port) throws Exception {
    try (Socket socket = new Socket("127.0.0.1", port)) {
      socket.setSoTimeout(2000);
      socket
          .getOutputStream()
          .write(
              ("GET /peers HTTP/1.1\r\nHost: ci.dev.acme.wohlben.eu\r\nConnection: close\r\n\r\n")
                  .getBytes(StandardCharsets.US_ASCII));
      InputStream in = socket.getInputStream();
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      byte[] buffer = new byte[1024];
      try {
        for (int read; (read = in.read(buffer)) >= 0; ) {
          out.write(buffer, 0, read);
        }
      } catch (java.net.SocketTimeoutException stillOpen) {
        // An open connection that stopped talking; what it sent is the answer.
      }
      return out.toString(StandardCharsets.US_ASCII);
    }
  }
}
