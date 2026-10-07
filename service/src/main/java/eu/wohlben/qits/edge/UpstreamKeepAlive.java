package eu.wohlben.qits.edge;

import io.netty.channel.Channel;
import io.netty.channel.ChannelOption;
import io.netty.channel.socket.nio.NioChannelOption;
import io.quarkus.runtime.annotations.RegisterForReflection;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import jdk.net.ExtendedSocketOptions;
import org.jboss.logging.Logger;

/**
 * TCP keepalive timers on every upstream socket: probe after 60 s of silence, every 10 s, and give
 * up after 3 unanswered probes — so a connection to a peer that vanished is closed within about a
 * minute and a half instead of never.
 *
 * <p><b>Why "never" was the old answer.</b> An SSE stream to a task swarm has redeployed away keeps
 * its edge-side socket ESTABLISHED: the old task leaves the VIP, so no FIN and no RST ever arrive;
 * the edge only reads, so there is no unacknowledged write to time out; and the proxy client's idle
 * timeout is zero on purpose (see {@link EdgeRouter#proxyClientOptions}). Each such stream held one
 * of the origin's 64 pool slots for good, and after 64 of them every request to that origin waited
 * out the acquisition bound and failed. {@code SO_KEEPALIVE} alone does not close that: Linux's
 * default is a first probe after two hours.
 *
 * <p><b>Set per channel, through Netty's {@link NioChannelOption}</b>, because no Vert.x 4.5
 * transport applies the {@code tcpKeepAlive*} options to a client socket — see {@link
 * UpstreamChannel}. The edge runs the NIO transport, where {@code jdk.net.ExtendedSocketOptions} is
 * the JDK's own spelling of {@code TCP_KEEPIDLE}, {@code TCP_KEEPINTVL} and {@code TCP_KEEPCNT}.
 *
 * <p><b>A refusal is loud once and never fatal.</b> An option the platform cannot set — another
 * transport, a kernel without it, a native image missing the JDK's {@code libextnet} — is logged at
 * ERROR once per process per option name, and the connection is used as it is. Failing the request
 * would turn a missing safety net into an outage.
 *
 * <p><b>Native image.</b> Every reference to {@link ExtendedSocketOptions} is inside a method body,
 * never in a static field, so initialising this class at image build time never initialises that
 * one — GraalVM pins it, and its platform implementation, to run-time initialisation. GraalVM's own
 * {@code JNIRegistrationJavaNet} registers {@code jdk.net.LinuxSocketOptions} and links {@code
 * libextnet} once {@code PlatformSocketOptions.create} is reachable, which the reference here makes
 * it. What is registered below is the one lookup that feature does not cover: {@code
 * sun.net.ext.ExtendedSocketOptions.getInstance} finds the {@code jdk.net} implementation by {@code
 * Class.forName}.
 */
@RegisterForReflection(classNames = "jdk.net.ExtendedSocketOptions")
final class UpstreamKeepAlive {

  private static final Logger LOG = Logger.getLogger(UpstreamKeepAlive.class);

  /** Seconds of silence before the first probe. */
  static final int IDLE_SECONDS = 60;

  /** Seconds between unanswered probes. */
  static final int INTERVAL_SECONDS = 10;

  /** Unanswered probes before the kernel closes the socket. */
  static final int COUNT = 3;

  /**
   * The option names already reported, so each is an ERROR once per process rather than once per
   * connection — the edge opens thousands, and the thousandth line says nothing the first did not.
   */
  private static final Set<String> REPORTED = ConcurrentHashMap.newKeySet();

  private UpstreamKeepAlive() {}

  /**
   * Set the three timers on one upstream connection's socket. Never throws: a connection whose
   * channel cannot be reached at all — see {@link UpstreamChannel} — is reported under {@code
   * channel}, once, like any option.
   *
   * @return the names of what could NOT be set
   */
  static Set<String> apply(io.vertx.core.http.HttpConnection connection) {
    Channel channel;
    try {
      channel = UpstreamChannel.of(connection);
    } catch (RuntimeException failure) {
      Set<String> refused = new LinkedHashSet<>();
      refuse("channel", 0, failure.toString(), failure, refused);
      return refused;
    }
    return apply(channel);
  }

  /**
   * Set the three timers on one upstream socket. Never throws.
   *
   * @return the names of the options that could NOT be set — empty on a healthy NIO socket
   */
  static Set<String> apply(Channel channel) {
    Set<String> refused = new LinkedHashSet<>();
    set(
        channel,
        "TCP_KEEPIDLE",
        () -> NioChannelOption.of(ExtendedSocketOptions.TCP_KEEPIDLE),
        IDLE_SECONDS,
        refused);
    set(
        channel,
        "TCP_KEEPINTERVAL",
        () -> NioChannelOption.of(ExtendedSocketOptions.TCP_KEEPINTERVAL),
        INTERVAL_SECONDS,
        refused);
    set(
        channel,
        "TCP_KEEPCOUNT",
        () -> NioChannelOption.of(ExtendedSocketOptions.TCP_KEEPCOUNT),
        COUNT,
        refused);
    return refused;
  }

  /**
   * One option. The option object is built inside the {@code try} on purpose: on a runtime without
   * {@code jdk.net} it is the reference itself that fails, with a {@link LinkageError}.
   */
  private static void set(
      Channel channel,
      String name,
      Supplier<ChannelOption<Integer>> option,
      int value,
      Set<String> refused) {
    try {
      if (!channel.config().setOption(option.get(), value)) {
        // Netty's answer for an option this channel type does not know — an epoll or a local
        // channel — or one the JDK channel does not list as supported.
        refuse(name, value, "the channel does not support it", null, refused);
      }
    } catch (RuntimeException | LinkageError failure) {
      refuse(name, value, failure.toString(), failure, refused);
    }
  }

  private static void refuse(
      String name, int value, String reason, Throwable failure, Set<String> refused) {
    refused.add(name);
    if (REPORTED.add(name)) {
      LOG.errorf(
          failure,
          "could not set %s=%d on an upstream socket (%s), so upstream sockets keep the kernel's"
              + " keepalive timers: a connection to an upstream that vanished can hold a pool slot"
              + " for hours. Requests are unaffected. Logged once per process.",
          name,
          value,
          reason);
    }
  }

  /** Whether {@code name} has been reported in this process. For the test that pins once-only. */
  static boolean reported(String name) {
    return REPORTED.contains(name);
  }
}
