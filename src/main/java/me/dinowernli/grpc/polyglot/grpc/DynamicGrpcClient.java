package me.dinowernli.grpc.polyglot.grpc;

import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import com.google.auth.Credentials;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.google.protobuf.Descriptors.MethodDescriptor;
import com.google.protobuf.DynamicMessage;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptors;
import io.grpc.MethodDescriptor.MethodType;
import io.grpc.auth.ClientAuthInterceptor;
import io.grpc.stub.ClientCalls;
import io.grpc.stub.StreamObserver;
import me.dinowernli.grpc.polyglot.protobuf.DynamicMessageMarshaller;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** A grpc client which operates on dynamic messages. */
public class DynamicGrpcClient {
  private static final Logger logger = LoggerFactory.getLogger(DynamicGrpcClient.class);
  private final MethodDescriptor protoMethodDescriptor;
  private final Channel channel;
  private final ListeningExecutorService executor;

  /** Creates a client for the supplied method, talking to the supplied endpoint. */
  public static DynamicGrpcClient create(MethodDescriptor protoMethod, Channel channel) {
    return new DynamicGrpcClient(protoMethod, channel, createExecutorService());
  }

  /**
   * Creates a client for the supplied method, talking to the supplied endpoint. Passes the
   * supplied credentials on every rpc call.
   */
  public static DynamicGrpcClient createWithCredentials(
      MethodDescriptor protoMethod,
      Channel channel,
      Credentials credentials) {
    ListeningExecutorService executor = createExecutorService();
    return new DynamicGrpcClient(
        protoMethod,
        ClientInterceptors.intercept(channel, new ClientAuthInterceptor(credentials, executor)),
        executor);
  }

  @VisibleForTesting
  DynamicGrpcClient(
      MethodDescriptor protoMethodDescriptor, Channel channel, ListeningExecutorService executor) {
    this.protoMethodDescriptor = protoMethodDescriptor;
    this.channel = channel;
    this.executor = executor;
  }

  /**
   * Makes an rpc to the remote endpoint and respects the supplied callback. Returns a future which
   * terminates once the call has ended. For calls which are single-request, this throws
   * {@link IllegalArgumentException} if the size of {@code requests} is not exactly 1.
   */
  public ListenableFuture<Void> call(
      ImmutableList<DynamicMessage> requests,
      StreamObserver<DynamicMessage> responseObserver,
      CallOptions callOptions) {
    Preconditions.checkArgument(!requests.isEmpty(), "Can't make call without any requests");
    MethodType methodType = getMethodType();
    long numRequests = requests.size();
    if (methodType == MethodType.UNARY) {
      logger.info("Making unary call");
      Preconditions.checkArgument(numRequests == 1,
          "Need exactly 1 request for unary call, but got: " + numRequests);
      return callUnary(requests.get(0), responseObserver, callOptions);
    } else if (methodType == MethodType.SERVER_STREAMING) {
      logger.info("Making server streaming call");
      Preconditions.checkArgument(numRequests == 1,
          "Need exactly 1 request for server streaming call, but got: " + numRequests);
      return callServerStreaming(requests.get(0), responseObserver, callOptions);
    } else if (methodType == MethodType.CLIENT_STREAMING) {
      logger.info("Making client streaming call with " + requests.size() + " requests");
      return callClientStreaming(requests, responseObserver, callOptions);
    } else {
      // Bidi streaming.
      logger.info("Making bidi streaming call with " + requests.size() + " requests");
      return callBidiStreaming(requests, responseObserver, callOptions);
    }
  }

  private ListenableFuture<Void> callBidiStreaming(
      ImmutableList<DynamicMessage> requests,
      StreamObserver<DynamicMessage> responseObserver,
      CallOptions callOptions) {
    DoneObserver<DynamicMessage> doneObserver = new DoneObserver<>();
    StreamObserver<DynamicMessage> requestObserver = ClientCalls.asyncBidiStreamingCall(
        createCall(callOptions),
        CompositeStreamObserver.of(responseObserver, doneObserver));
    requests.forEach(requestObserver::onNext);
    requestObserver.onCompleted();
    return submitWaitTask(doneObserver);
  }

  private ListenableFuture<Void> callClientStreaming(
      ImmutableList<DynamicMessage> requests,
      StreamObserver<DynamicMessage> responseObserver,
      CallOptions callOptions) {
    DoneObserver<DynamicMessage> doneObserver = new DoneObserver<>();
    StreamObserver<DynamicMessage> requestObserver = ClientCalls.asyncClientStreamingCall(
        createCall(callOptions),
        CompositeStreamObserver.of(responseObserver, doneObserver));
    requests.forEach(requestObserver::onNext);
    requestObserver.onCompleted();
    return submitWaitTask(doneObserver);
  }

  private ListenableFuture<Void> callServerStreaming(
      DynamicMessage request,
      StreamObserver<DynamicMessage> responseObserver,
      CallOptions callOptions) {
    DoneObserver<DynamicMessage> doneObserver = new DoneObserver<>();
    ClientCalls.asyncServerStreamingCall(
        createCall(callOptions),
        request,
        CompositeStreamObserver.of(responseObserver, doneObserver));
    return submitWaitTask(doneObserver);
  }

  private ListenableFuture<Void> callUnary(
      DynamicMessage request,
      StreamObserver<DynamicMessage> responseObserver,
      CallOptions callOptions) {
    ListenableFuture<DynamicMessage> response =
        ClientCalls.futureUnaryCall(createCall(callOptions), request);

    // TODO(dino): Consider using asyncUnaryCall for symmetry with the other calls.

    DoneObserver<DynamicMessage> doneObserver = new DoneObserver<>();
    SingleResponseCallback<DynamicMessage> callback = new SingleResponseCallback<>(
        CompositeStreamObserver.of(responseObserver, doneObserver));
    Futures.addCallback(response, callback);
    return submitWaitTask(doneObserver);
  }

  private ClientCall<DynamicMessage, DynamicMessage> createCall(CallOptions callOptions) {
    return channel.newCall(createGrpcMethodDescriptor(), callOptions);
  }

  /** Returns a {@link ListenableFuture} which completes when the supplied observer is done. */
  private ListenableFuture<Void> submitWaitTask(DoneObserver<?> doneObserver) {
    return executor.submit(new Callable<Void>() {
      @Override
      public Void call() throws Exception {
        synchronized (doneObserver) {
          doneObserver.wait();
        }
        return null;
      }
    });
  }

  private io.grpc.MethodDescriptor<DynamicMessage, DynamicMessage> createGrpcMethodDescriptor() {
    return io.grpc.MethodDescriptor.<DynamicMessage, DynamicMessage>create(
        getMethodType(),
        getFullMethodName(),
        new DynamicMessageMarshaller(protoMethodDescriptor.getInputType()),
        new DynamicMessageMarshaller(protoMethodDescriptor.getOutputType()));
  }

  private String getFullMethodName() {
    String serviceName = protoMethodDescriptor.getService().getFullName();
    String methodName = protoMethodDescriptor.getName();
    return io.grpc.MethodDescriptor.generateFullMethodName(serviceName, methodName);
  }

  /** Returns the appropriate method type based on whether the client or server expect streams. */
  private MethodType getMethodType() {
    boolean clientStreaming = protoMethodDescriptor.toProto().getClientStreaming();
    boolean serverStreaming = protoMethodDescriptor.toProto().getServerStreaming();

    if (!clientStreaming && !serverStreaming) {
      return MethodType.UNARY;
    } else if (!clientStreaming && serverStreaming) {
      return MethodType.SERVER_STREAMING;
    } else if (clientStreaming && !serverStreaming) {
      return MethodType.CLIENT_STREAMING;
    } else {
      return MethodType.BIDI_STREAMING;
    }
  }

  /**
   * Returns an executor in daemon-mode, allowing callers to actively decide whether they want to
   * wait for rpc streams to complete.
   */
  private static ListeningExecutorService createExecutorService() {
    return MoreExecutors.listeningDecorator(
        Executors.newCachedThreadPool(new ThreadFactoryBuilder().setDaemon(true).build()));
  }
}
