/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.qpid.jms.provider.amqp;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.util.ReferenceCountUtil;

import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import javax.jms.JMSException;

import org.apache.qpid.jms.JmsTemporaryDestination;
import org.apache.qpid.jms.message.JmsInboundMessageDispatch;
import org.apache.qpid.jms.message.JmsMessageFactory;
import org.apache.qpid.jms.message.JmsOutboundMessageDispatch;
import org.apache.qpid.jms.meta.JmsConnectionInfo;
import org.apache.qpid.jms.meta.JmsConsumerId;
import org.apache.qpid.jms.meta.JmsConsumerInfo;
import org.apache.qpid.jms.meta.JmsDefaultResourceVisitor;
import org.apache.qpid.jms.meta.JmsProducerId;
import org.apache.qpid.jms.meta.JmsProducerInfo;
import org.apache.qpid.jms.meta.JmsResource;
import org.apache.qpid.jms.meta.JmsResourceVistor;
import org.apache.qpid.jms.meta.JmsSessionId;
import org.apache.qpid.jms.meta.JmsSessionInfo;
import org.apache.qpid.jms.meta.JmsTransactionInfo;
import org.apache.qpid.jms.provider.AsyncResult;
import org.apache.qpid.jms.provider.NoOpAsyncResult;
import org.apache.qpid.jms.provider.Provider;
import org.apache.qpid.jms.provider.ProviderClosedException;
import org.apache.qpid.jms.provider.ProviderConstants.ACK_TYPE;
import org.apache.qpid.jms.provider.ProviderFuture;
import org.apache.qpid.jms.provider.ProviderListener;
import org.apache.qpid.jms.transports.TransportFactory;
import org.apache.qpid.jms.transports.TransportListener;
import org.apache.qpid.jms.util.IOExceptionSupport;
import org.apache.qpid.proton.engine.Collector;
import org.apache.qpid.proton.engine.Connection;
import org.apache.qpid.proton.engine.Event;
import org.apache.qpid.proton.engine.Event.Type;
import org.apache.qpid.proton.engine.Sasl;
import org.apache.qpid.proton.engine.Transport;
import org.apache.qpid.proton.engine.impl.CollectorImpl;
import org.apache.qpid.proton.engine.impl.ProtocolTracer;
import org.apache.qpid.proton.engine.impl.TransportImpl;
import org.apache.qpid.proton.framing.TransportFrame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * An AMQP v1.0 Provider.
 *
 * The AMQP Provider is bonded to a single remote broker instance.  The provider will attempt
 * to connect to only that instance and once failed can not be recovered.  For clients that
 * wish to implement failover type connections a new AMQP Provider instance must be created
 * and state replayed from the JMS layer using the standard recovery process defined in the
 * JMS Provider API.
 *
 * All work within this Provider is serialized to a single Thread.  Any asynchronous exceptions
 * will be dispatched from that Thread and all in-bound requests are handled there as well.
 */
public class AmqpProvider implements Provider, TransportListener {

    private static final Logger LOG = LoggerFactory.getLogger(AmqpProvider.class);

    private static final Logger TRACE_BYTES = LoggerFactory.getLogger(AmqpConnection.class.getPackage().getName() + ".BYTES");
    private static final Logger TRACE_FRAMES = LoggerFactory.getLogger(AmqpConnection.class.getPackage().getName() + ".FRAMES");
    private static final int DEFAULT_MAX_FRAME_SIZE = 1024 * 1024 * 1;
    // NOTE: Limit default channel max to signed short range to deal with
    //       brokers that don't currently handle the unsigned range well.
    private static final int DEFAULT_CHANNEL_MAX = 32767;
    private static final AtomicInteger PROVIDER_SEQUENCE = new AtomicInteger();
    private static final NoOpAsyncResult NOOP_REQUEST = new NoOpAsyncResult();

    private ProviderListener listener;
    private AmqpConnection connection;
    private org.apache.qpid.jms.transports.Transport transport;
    private String transportType = AmqpProviderFactory.DEFAULT_TRANSPORT_TYPE;
    private boolean traceFrames;
    private boolean traceBytes;
    private boolean presettleConsumers;
    private boolean presettleProducers;
    private long connectTimeout = JmsConnectionInfo.DEFAULT_CONNECT_TIMEOUT;
    private long closeTimeout = JmsConnectionInfo.DEFAULT_CLOSE_TIMEOUT;
    private long requestTimeout = JmsConnectionInfo.DEFAULT_REQUEST_TIMEOUT;
    private long sendTimeout = JmsConnectionInfo.DEFAULT_SEND_TIMEOUT;
    private int channelMax = DEFAULT_CHANNEL_MAX;

    private final URI remoteURI;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final ScheduledExecutorService serializer;
    private final Transport protonTransport = Transport.Factory.create();
    private final Collector protonCollector = new CollectorImpl();

    /**
     * Create a new instance of an AmqpProvider bonded to the given remote URI.
     *
     * @param remoteURI
     *        The URI of the AMQP broker this Provider instance will connect to.
     */
    public AmqpProvider(URI remoteURI) {
        this.remoteURI = remoteURI;
        this.serializer = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {

            @Override
            public Thread newThread(Runnable runner) {
                Thread serial = new Thread(runner);
                serial.setDaemon(true);
                serial.setName(AmqpProvider.this.getClass().getSimpleName() + ":(" +
                               PROVIDER_SEQUENCE.incrementAndGet() + "):[" +
                               getRemoteURI() + "]");
                return serial;
            }
        });

        updateTracer();
    }

    @Override
    public void connect() throws IOException {
        checkClosed();

        try {
            transport = TransportFactory.create(getTransportType(), getRemoteURI());
        } catch (Exception e) {
            throw IOExceptionSupport.create(e);
        }
        transport.setTransportListener(this);
        transport.connect();
    }

    @Override
    public void start() throws IOException, IllegalStateException {
        checkClosed();

        if (listener == null) {
            throw new IllegalStateException("No ProviderListener registered.");
        }
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            final ProviderFuture request = new ProviderFuture();
            serializer.execute(new Runnable() {

                @Override
                public void run() {
                    try {

                        // If we are not connected then there is nothing we can do now
                        // just signal success.
                        if (transport == null || !transport.isConnected()) {
                            request.onSuccess();
                        }

                        if (connection != null) {
                            connection.close(request);
                            pumpToProtonTransport(request);
                        } else {
                            request.onSuccess();
                        }
                    } catch (Exception e) {
                        LOG.debug("Caught exception while closing proton connection: {}", e.getMessage());
                    }
                }
            });

            try {
                if (closeTimeout < 0) {
                    request.sync();
                } else {
                    request.sync(closeTimeout, TimeUnit.MILLISECONDS);
                }
            } catch (IOException e) {
                LOG.warn("Error caught while closing Provider: ", e.getMessage());
            } finally {
                if (transport != null) {
                    try {
                        transport.close();
                    } catch (Exception e) {
                        LOG.debug("Caught exception while closing down Transport: {}", e.getMessage());
                    }
                }

                serializer.shutdown();
            }
        }
    }

    @Override
    public void create(final JmsResource resource, final AsyncResult request) throws IOException, JMSException {
        checkClosed();
        serializer.execute(new Runnable() {

            @Override
            public void run() {
                try {
                    checkClosed();
                    resource.visit(new JmsResourceVistor() {

                        @Override
                        public void processSessionInfo(JmsSessionInfo sessionInfo) throws Exception {
                            AmqpSession session = connection.createSession(sessionInfo);
                            session.open(request);
                        }

                        @Override
                        public void processProducerInfo(JmsProducerInfo producerInfo) throws Exception {
                            AmqpSession session = connection.getSession(producerInfo.getParentId());
                            AmqpProducer producer = session.createProducer(producerInfo);
                            producer.open(request);
                        }

                        @Override
                        public void processConsumerInfo(JmsConsumerInfo consumerInfo) throws Exception {
                            AmqpSession session = connection.getSession(consumerInfo.getParentId());
                            AmqpConsumer consumer = session.createConsumer(consumerInfo);
                            consumer.open(request);
                        }

                        @Override
                        public void processConnectionInfo(JmsConnectionInfo connectionInfo) throws Exception {
                            closeTimeout = connectionInfo.getCloseTimeout();
                            connectTimeout = connectionInfo.getConnectTimeout();
                            sendTimeout = connectionInfo.getSendTimeout();
                            requestTimeout = connectionInfo.getRequestTimeout();

                            Connection protonConnection = Connection.Factory.create();
                            protonTransport.setMaxFrameSize(getMaxFrameSize());
                            protonTransport.setChannelMax(getChannelMax());
                            protonTransport.bind(protonConnection);
                            protonConnection.collect(protonCollector);
                            Sasl sasl = protonTransport.sasl();
                            if (sasl != null) {
                                sasl.client();
                            }
                            connection = new AmqpConnection(AmqpProvider.this, protonConnection, sasl, connectionInfo);
                            connection.open(new AsyncResult() {

                                @Override
                                public void onSuccess() {
                                    fireConnectionEstablished();
                                    request.onSuccess();
                                }

                                @Override
                                public void onFailure(Throwable result) {
                                    request.onFailure(result);
                                }

                                @Override
                                public boolean isComplete() {
                                    return request.isComplete();
                                }
                            });
                        }

                        @Override
                        public void processDestination(JmsTemporaryDestination destination) throws Exception {
                            if (destination.isTemporary()) {
                                AmqpTemporaryDestination temporary = connection.createTemporaryDestination(destination);
                                temporary.open(request);
                            } else {
                                request.onSuccess();
                            }
                        }

                        @Override
                        public void processTransactionInfo(JmsTransactionInfo transactionInfo) throws Exception {
                            AmqpSession session = connection.getSession(transactionInfo.getParentId());
                            session.begin(transactionInfo.getTransactionId(), request);
                        }
                    });

                    pumpToProtonTransport(request);
                } catch (Exception error) {
                    request.onFailure(error);
                }
            }
        });
    }

    @Override
    public void start(final JmsResource resource, final AsyncResult request) throws IOException {
        checkClosed();
        serializer.execute(new Runnable() {

            @Override
            public void run() {
                try {
                    checkClosed();
                    resource.visit(new JmsDefaultResourceVisitor() {

                        @Override
                        public void processConsumerInfo(JmsConsumerInfo consumerInfo) throws Exception {
                            AmqpSession session = connection.getSession(consumerInfo.getParentId());
                            AmqpConsumer consumer = session.getConsumer(consumerInfo);
                            consumer.start(request);
                        }
                    });

                    pumpToProtonTransport(request);
                } catch (Exception error) {
                    request.onFailure(error);
                }
            }
        });
    }

    @Override
    public void stop(final JmsResource resource, final AsyncResult request) throws IOException {
        checkClosed();
        serializer.execute(new Runnable() {

            @Override
            public void run() {
                try {
                    checkClosed();
                    resource.visit(new JmsDefaultResourceVisitor() {

                        @Override
                        public void processConsumerInfo(JmsConsumerInfo consumerInfo) throws Exception {
                            AmqpSession session = connection.getSession(consumerInfo.getParentId());
                            AmqpConsumer consumer = session.getConsumer(consumerInfo);
                            consumer.stop(request);
                        }
                    });

                    pumpToProtonTransport(request);
                } catch (Exception error) {
                    request.onFailure(error);
                }
            }
        });
    }

    @Override
    public void destroy(final JmsResource resource, final AsyncResult request) throws IOException {
        checkClosed();
        serializer.execute(new Runnable() {

            @Override
            public void run() {
                try {
                    checkClosed();
                    resource.visit(new JmsDefaultResourceVisitor() {

                        @Override
                        public void processSessionInfo(JmsSessionInfo sessionInfo) throws Exception {
                            AmqpSession session = connection.getSession(sessionInfo.getSessionId());
                            session.close(request);
                        }

                        @Override
                        public void processProducerInfo(JmsProducerInfo producerInfo) throws Exception {
                            AmqpSession session = connection.getSession(producerInfo.getParentId());
                            AmqpProducer producer = session.getProducer(producerInfo);
                            producer.close(request);
                        }

                        @Override
                        public void processConsumerInfo(JmsConsumerInfo consumerInfo) throws Exception {
                            AmqpSession session = connection.getSession(consumerInfo.getParentId());
                            AmqpConsumer consumer = session.getConsumer(consumerInfo);
                            consumer.close(request);
                        }

                        @Override
                        public void processConnectionInfo(JmsConnectionInfo connectionInfo) throws Exception {
                            connection.close(request);
                        }

                        @Override
                        public void processDestination(JmsTemporaryDestination destination) throws Exception {
                            AmqpTemporaryDestination temporary = connection.getTemporaryDestination(destination);
                            if (temporary != null) {
                                temporary.close(request);
                            } else {
                                LOG.debug("Could not find temporary destination {} to delete.", destination);
                                request.onSuccess();
                            }
                        }
                    });

                    pumpToProtonTransport(request);
                } catch (Exception error) {
                    request.onFailure(error);
                }
            }
        });
    }

    @Override
    public void send(final JmsOutboundMessageDispatch envelope, final AsyncResult request) throws IOException {
        checkClosed();
        serializer.execute(new Runnable() {

            @Override
            public void run() {
                try {
                    checkClosed();

                    JmsProducerId producerId = envelope.getProducerId();
                    AmqpProducer producer = null;

                    if (producerId.getProviderHint() instanceof AmqpFixedProducer) {
                        producer = (AmqpFixedProducer) producerId.getProviderHint();
                    } else {
                        AmqpSession session = connection.getSession(producerId.getParentId());
                        producer = session.getProducer(producerId);
                    }

                    boolean couldSend = producer.send(envelope, request);
                    pumpToProtonTransport(request);
                    if (couldSend && envelope.isSendAsync()) {
                        request.onSuccess();
                    }
                } catch (Exception error) {
                    request.onFailure(error);
                }
            }
        });
    }

    @Override
    public void acknowledge(final JmsSessionId sessionId, final AsyncResult request) throws IOException {
        checkClosed();
        serializer.execute(new Runnable() {

            @Override
            public void run() {
                try {
                    checkClosed();
                    AmqpSession amqpSession = connection.getSession(sessionId);
                    amqpSession.acknowledge();
                    pumpToProtonTransport(request);
                    request.onSuccess();
                } catch (Exception error) {
                    request.onFailure(error);
                }
            }
        });
    }

    @Override
    public void acknowledge(final JmsInboundMessageDispatch envelope, final ACK_TYPE ackType, final AsyncResult request) throws IOException {
        checkClosed();
        serializer.execute(new Runnable() {

            @Override
            public void run() {
                try {
                    checkClosed();

                    JmsConsumerId consumerId = envelope.getConsumerId();
                    AmqpConsumer consumer = null;

                    if (consumerId.getProviderHint() instanceof AmqpConsumer) {
                        consumer = (AmqpConsumer) consumerId.getProviderHint();
                    } else {
                        AmqpSession session = connection.getSession(consumerId.getParentId());
                        consumer = session.getConsumer(consumerId);
                    }

                    consumer.acknowledge(envelope, ackType);

                    if (consumer.getSession().isAsyncAck()) {
                        request.onSuccess();
                        pumpToProtonTransport(request);
                    } else {
                        pumpToProtonTransport(request);
                        request.onSuccess();
                    }
                } catch (Exception error) {
                    request.onFailure(error);
                }
            }
        });
    }

    @Override
    public void commit(final JmsSessionId sessionId, final AsyncResult request) throws IOException {
        checkClosed();
        serializer.execute(new Runnable() {

            @Override
            public void run() {
                try {
                    checkClosed();
                    AmqpSession session = connection.getSession(sessionId);
                    session.commit(request);
                    pumpToProtonTransport(request);
                } catch (Exception error) {
                    request.onFailure(error);
                }
            }
        });
    }

    @Override
    public void rollback(final JmsSessionId sessionId, final AsyncResult request) throws IOException {
        checkClosed();
        serializer.execute(new Runnable() {

            @Override
            public void run() {
                try {
                    checkClosed();
                    AmqpSession session = connection.getSession(sessionId);
                    session.rollback(request);
                    pumpToProtonTransport(request);
                } catch (Exception error) {
                    request.onFailure(error);
                }
            }
        });
    }

    @Override
    public void recover(final JmsSessionId sessionId, final AsyncResult request) throws IOException {
        checkClosed();
        serializer.execute(new Runnable() {

            @Override
            public void run() {
                try {
                    checkClosed();
                    AmqpSession session = connection.getSession(sessionId);
                    session.recover();
                    pumpToProtonTransport(request);
                    request.onSuccess();
                } catch (Exception error) {
                    request.onFailure(error);
                }
            }
        });
    }

    @Override
    public void unsubscribe(final String subscription, final AsyncResult request) throws IOException {
        checkClosed();
        serializer.execute(new Runnable() {

            @Override
            public void run() {
                try {
                    checkClosed();
                    connection.unsubscribe(subscription, request);
                    pumpToProtonTransport(request);
                } catch (Exception error) {
                    request.onFailure(error);
                }
            }
        });
    }

    @Override
    public void pull(final JmsConsumerId consumerId, final long timeout, final AsyncResult request) throws IOException {
        checkClosed();
        serializer.execute(new Runnable() {

            @Override
            public void run() {
                try {
                    checkClosed();
                    AmqpConsumer consumer = null;

                    if (consumerId.getProviderHint() instanceof AmqpConsumer) {
                        consumer = (AmqpConsumer) consumerId.getProviderHint();
                    } else {
                        AmqpSession session = connection.getSession(consumerId.getParentId());
                        consumer = session.getConsumer(consumerId);
                    }

                    consumer.pull(timeout);
                    pumpToProtonTransport(request);
                    request.onSuccess();
                } catch (Exception error) {
                    request.onFailure(error);
                }
            }
        });
    }

    //---------- Event handlers and Utility methods  -------------------------//

    private void updateTracer() {
        if (isTraceFrames()) {
            ((TransportImpl) protonTransport).setProtocolTracer(new ProtocolTracer() {
                @Override
                public void receivedFrame(TransportFrame transportFrame) {
                    TRACE_FRAMES.trace("RECV: {}", transportFrame.getBody());
                }

                @Override
                public void sentFrame(TransportFrame transportFrame) {
                    TRACE_FRAMES.trace("SENT: {}", transportFrame.getBody());
                }
            });
        }
    }

    @Override
    public void onData(final ByteBuf input) {

        // We need to retain until the serializer gets around to processing it.
        ReferenceCountUtil.retain(input);

        serializer.execute(new Runnable() {

            @Override
            public void run() {
                LOG.trace("Received from Broker {} bytes: {}", input.readableBytes(), input);

                ByteBuffer source = input.nioBuffer();

                do {
                    ByteBuffer buffer = protonTransport.getInputBuffer();
                    int limit = Math.min(buffer.remaining(), source.remaining());
                    ByteBuffer duplicate = source.duplicate();
                    duplicate.limit(source.position() + limit);
                    buffer.put(duplicate);
                    protonTransport.processInput();
                    source.position(source.position() + limit);
                } while (source.hasRemaining());

                ReferenceCountUtil.release(input);

                // Process the state changes from the latest data and then answer back
                // any pending updates to the Broker.
                processUpdates();
                pumpToProtonTransport(NOOP_REQUEST);
            }
        });
    }

    /**
     * Callback method for the Transport to report connection errors.  When called
     * the method will queue a new task to fire the failure error back to the listener.
     *
     * @param error
     *        the error that causes the transport to fail.
     */
    @Override
    public void onTransportError(final Throwable error) {
        if (!serializer.isShutdown()) {
            serializer.execute(new Runnable() {
                @Override
                public void run() {
                    LOG.info("Transport failed: {}", error.getMessage());
                    if (!closed.get()) {
                        fireProviderException(error);
                        if (connection != null) {
                            connection.closed();
                        }
                    }
                }
            });
        }
    }

    /**
     * Callback method for the Transport to report that the underlying connection
     * has closed.  When called this method will queue a new task that will check for
     * the closed state on this transport and if not closed then an exception is raised
     * to the registered ProviderListener to indicate connection loss.
     */
    @Override
    public void onTransportClosed() {
        if (!serializer.isShutdown()) {
            serializer.execute(new Runnable() {
                @Override
                public void run() {
                    LOG.debug("Transport connection remotely closed");
                    if (!closed.get()) {
                        fireProviderException(new IOException("Connection remotely closed."));
                        if (connection != null) {
                            connection.closed();
                        }
                    }
                }
            });
        }
    }

    private void processUpdates() {
        try {
            Event protonEvent = null;
            while ((protonEvent = protonCollector.peek()) != null) {
                if (!protonEvent.getType().equals(Type.TRANSPORT)) {
                    LOG.trace("New Proton Event: {}", protonEvent.getType());
                }

                AmqpResource amqpResource = null;
                switch (protonEvent.getType()) {
                    case CONNECTION_REMOTE_CLOSE:
                        amqpResource = (AmqpResource) protonEvent.getConnection().getContext();
                        amqpResource.processRemoteClose(this);
                        break;
                    case CONNECTION_REMOTE_OPEN:
                        amqpResource = (AmqpResource) protonEvent.getConnection().getContext();
                        amqpResource.processRemoteOpen(this);
                        break;
                    case SESSION_REMOTE_CLOSE:
                        amqpResource = (AmqpSession) protonEvent.getSession().getContext();
                        amqpResource.processRemoteClose(this);
                        break;
                    case SESSION_REMOTE_OPEN:
                        amqpResource = (AmqpSession) protonEvent.getSession().getContext();
                        amqpResource.processRemoteOpen(this);
                        break;
                    case LINK_REMOTE_CLOSE:
                        LOG.info("Link closed: {}", protonEvent.getLink().getContext());
                        amqpResource = (AmqpResource) protonEvent.getLink().getContext();
                        amqpResource.processRemoteClose(this);
                        break;
                    case LINK_REMOTE_DETACH:
                        LOG.info("Link detach: {}", protonEvent.getLink().getContext());
                        amqpResource = (AmqpResource) protonEvent.getLink().getContext();
                        amqpResource.processRemoteDetach(this);
                        break;
                    case LINK_REMOTE_OPEN:
                        amqpResource = (AmqpResource) protonEvent.getLink().getContext();
                        amqpResource.processRemoteOpen(this);
                        break;
                    case LINK_FLOW:
                        amqpResource = (AmqpResource) protonEvent.getLink().getContext();
                        amqpResource.processFlowUpdates(this);
                        break;
                    case DELIVERY:
                        amqpResource = (AmqpResource) protonEvent.getLink().getContext();
                        amqpResource.processDeliveryUpdates(this);
                        break;
                    default:
                        break;
                }

                protonCollector.pop();
            }

            // We have to do this to pump SASL bytes in as SASL is not event driven yet.
            if (connection != null) {
                connection.processSaslAuthentication();
            }
        } catch (Exception ex) {
            LOG.warn("Caught Exception during update processing: {}", ex.getMessage(), ex);
            fireProviderException(ex);
        }
    }

    private void pumpToProtonTransport(AsyncResult request) {
        try {
            boolean done = false;
            while (!done) {
                ByteBuffer toWrite = protonTransport.getOutputBuffer();
                if (toWrite != null && toWrite.hasRemaining()) {
                    ByteBuf outbound = transport.allocateSendBuffer(toWrite.remaining());
                    outbound.writeBytes(toWrite);

                    if (isTraceBytes()) {
                        TRACE_BYTES.info("Sending: {}", ByteBufUtil.hexDump(outbound));
                    }

                    transport.send(outbound);
                    protonTransport.outputConsumed();
                } else {
                    done = true;
                }
            }
        } catch (IOException e) {
            fireProviderException(e);
            request.onFailure(e);
        }
    }

    void fireConnectionEstablished() {
        ProviderListener listener = this.listener;
        if (listener != null) {
            listener.onConnectionEstablished(remoteURI);
        }
    }

    void fireProviderException(Throwable ex) {
        ProviderListener listener = this.listener;
        if (listener != null) {
            listener.onConnectionFailure(IOExceptionSupport.create(ex));
        }
    }

    void fireResourceRemotelyClosed(JmsResource resource, Exception ex) {
        ProviderListener listener = this.listener;
        if (listener != null) {
            listener.onResourceRemotelyClosed(resource, ex);
        }
    }

    private void checkClosed() throws ProviderClosedException {
        if (closed.get()) {
            throw new ProviderClosedException("This Provider is already closed");
        }
    }

    //---------- Property Setters and Getters --------------------------------//

    @Override
    public JmsMessageFactory getMessageFactory() {
        if (connection == null) {
            throw new RuntimeException("Message Factory is not accessible when not connected.");
        }
        return connection.getAmqpMessageFactory();
    }

    public void setTraceFrames(boolean trace) {
        this.traceFrames = trace;
        updateTracer();
    }

    public boolean isTraceFrames() {
        return this.traceFrames;
    }

    public void setTraceBytes(boolean trace) {
        this.traceBytes = trace;
    }

    public boolean isTraceBytes() {
        return this.traceBytes;
    }

    public long getCloseTimeout() {
        return this.closeTimeout;
    }

    public long getConnectTimeout() {
        return connectTimeout;
    }

    public long getRequestTimeout() {
        return requestTimeout;
    }

    public long getSendTimeout() {
        return sendTimeout;
    }

    public void setPresettle(boolean presettle) {
        setPresettleConsumers(presettle);
        setPresettleProducers(presettle);
    }

    public boolean isPresettleConsumers() {
        return this.presettleConsumers;
    }

    public void setPresettleConsumers(boolean presettle) {
        this.presettleConsumers = presettle;
    }

    public boolean isPresettleProducers() {
        return this.presettleProducers;
    }

    public void setPresettleProducers(boolean presettle) {
        this.presettleProducers = presettle;
    }

    /**
     * @return the currently set Max Frame Size value.
     */
    public int getMaxFrameSize() {
        return DEFAULT_MAX_FRAME_SIZE;
    }

    @Override
    public String toString() {
        return "AmqpProvider: " + getRemoteURI().getHost() + ":" + getRemoteURI().getPort();
    }

    public int getChannelMax() {
        return channelMax;
    }

    public void setChannelMax(int channelMax) {
        this.channelMax = channelMax;
    }

    String getTransportType() {
        return transportType;
    }

    void setTransportType(String transportType) {
        this.transportType = transportType;
    }

    @Override
    public void setProviderListener(ProviderListener listener) {
        this.listener = listener;
    }

    @Override
    public ProviderListener getProviderListener() {
        return listener;
    }

    @Override
    public URI getRemoteURI() {
        return remoteURI;
    }
}
