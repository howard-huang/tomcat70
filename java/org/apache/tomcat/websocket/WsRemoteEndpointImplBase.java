/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.apache.tomcat.websocket;

import java.io.IOException;
import java.io.OutputStream;
import java.io.Writer;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CoderResult;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.websocket.DeploymentException;
import javax.websocket.EncodeException;
import javax.websocket.Encoder;
import javax.websocket.EndpointConfig;
import javax.websocket.RemoteEndpoint;
import javax.websocket.SendHandler;
import javax.websocket.SendResult;

import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;
import org.apache.tomcat.util.buf.Utf8Encoder;
import org.apache.tomcat.util.res.StringManager;

public abstract class WsRemoteEndpointImplBase implements RemoteEndpoint {

    private static final StringManager sm =
            StringManager.getManager(Constants.PACKAGE_NAME);

    // Milliseconds so this is 20 seconds
    private static final long DEFAULT_BLOCKING_SEND_TIMEOUT = 20 * 1000;

    public static final String BLOCKING_SEND_TIMEOUT_PROPERTY =
            "org.apache.tomcat.websocket.BLOCKING_SEND_TIMEOUT";

    private final Log log = LogFactory.getLog(WsRemoteEndpointImplBase.class);

    private final StateMachine stateMachine = new StateMachine();

    private boolean messagePartInProgress = false;
    private final Queue<MessagePart> messagePartQueue = new ArrayDeque<MessagePart>();
    private final Object messagePartLock = new Object();

    // State
    private volatile boolean closed = false;
    private boolean fragmented = false;
    private boolean nextFragmented = false;
    private boolean text = false;
    private boolean nextText = false;

    // Max size of WebSocket header is 14 bytes
    private final ByteBuffer headerBuffer = ByteBuffer.allocate(14);
    private final ByteBuffer outputBuffer = ByteBuffer.allocate(8192);
    private final CharsetEncoder encoder = new Utf8Encoder();
    private final ByteBuffer encoderBuffer = ByteBuffer.allocate(8192);
    private final AtomicBoolean batchingAllowed = new AtomicBoolean(false);
    private volatile long sendTimeout = -1;
    private WsSession wsSession;
    private List<EncoderEntry> encoderEntries = new ArrayList<EncoderEntry>();

    public long getSendTimeout() {
        return sendTimeout;
    }


    public void setSendTimeout(long timeout) {
        this.sendTimeout = timeout;
    }


    @Override
    public void setBatchingAllowed(boolean batchingAllowed) throws IOException {
        boolean oldValue = this.batchingAllowed.getAndSet(batchingAllowed);

        if (oldValue && !batchingAllowed) {
            flushBatch();
        }
    }


    @Override
    public boolean getBatchingAllowed() {
        return batchingAllowed.get();
    }


    @Override
    public void flushBatch() throws IOException {
        startMessageBlock(Constants.INTERNAL_OPCODE_FLUSH, null, true);
    }


    public void sendBytes(ByteBuffer data) throws IOException {
        stateMachine.binaryStart();
        startMessageBlock(Constants.OPCODE_BINARY, data, true);
        stateMachine.complete(true);
    }


    public Future<Void> sendBytesByFuture(ByteBuffer data) {
        FutureToSendHandler f2sh = new FutureToSendHandler(wsSession);
        sendBytesByCompletion(data, f2sh);
        return f2sh;
    }


    public void sendBytesByCompletion(ByteBuffer data, SendHandler handler) {
        StateUpdateSendHandler sush = new StateUpdateSendHandler(handler);
        stateMachine.binaryStart();
        startMessage(Constants.OPCODE_BINARY, data, true, sush);
    }


    public void sendPartialBytes(ByteBuffer partialByte, boolean last)
            throws IOException {
        stateMachine.binaryPartialStart();
        startMessageBlock(Constants.OPCODE_BINARY, partialByte, last);
        stateMachine.complete(last);
    }


    @Override
    public void sendPing(ByteBuffer applicationData) throws IOException,
            IllegalArgumentException {
        startMessageBlock(Constants.OPCODE_PING, applicationData, true);
    }


    @Override
    public void sendPong(ByteBuffer applicationData) throws IOException,
            IllegalArgumentException {
        startMessageBlock(Constants.OPCODE_PONG, applicationData, true);
    }


    public void sendString(String text) throws IOException {
        stateMachine.textStart();
        sendPartialString(CharBuffer.wrap(text), true);
    }


    public Future<Void> sendStringByFuture(String text) {
        FutureToSendHandler f2sh = new FutureToSendHandler(wsSession);
        sendStringByCompletion(text, f2sh);
        return f2sh;
    }


    public void sendStringByCompletion(String text, SendHandler handler) {
        stateMachine.textStart();
        TextMessageSendHandler tmsh = new TextMessageSendHandler(handler,
                CharBuffer.wrap(text), true, encoder, encoderBuffer, this);
        tmsh.write();
        // TextMessageSendHandler will update stateMachine when it completes
    }


    public void sendPartialString(String fragment, boolean isLast)
            throws IOException {
        stateMachine.textPartialStart();
        sendPartialString(CharBuffer.wrap(fragment), isLast);
    }


    public OutputStream getSendStream() {
        stateMachine.streamStart();
        return new WsOutputStream(this);
    }


    public Writer getSendWriter() {
        stateMachine.writeStart();
        return new WsWriter(this);
    }


    void sendPartialString(CharBuffer part, boolean last) throws IOException {
        try {
            // Get the timeout before we send the message. The message may
            // trigger a session close and depending on timing the client
            // session may close before we can read the timeout.
            long timeout = getBlockingSendTimeout();
            FutureToSendHandler f2sh = new FutureToSendHandler(wsSession);
            TextMessageSendHandler tmsh = new TextMessageSendHandler(f2sh, part,
                    last, encoder, encoderBuffer, this);
            tmsh.write();
            if (timeout == -1) {
                f2sh.get();
            } else {
                f2sh.get(timeout, TimeUnit.MILLISECONDS);
            }
        } catch (InterruptedException e) {
            throw new IOException(e);
        } catch (ExecutionException e) {
            throw new IOException(e);
        } catch (TimeoutException e) {
            throw new IOException(e);
        }
    }


    void startMessageBlock(byte opCode, ByteBuffer payload, boolean last)
            throws IOException {
        // Get the timeout before we send the message. The message may
        // trigger a session close and depending on timing the client
        // session may close before we can read the timeout.
        long timeout = getBlockingSendTimeout();
        FutureToSendHandler f2sh = new FutureToSendHandler(wsSession);
        startMessage(opCode, payload, last, f2sh);
        try {
            if (timeout == -1) {
                f2sh.get();
            } else {
                f2sh.get(timeout, TimeUnit.MILLISECONDS);
            }
        } catch (InterruptedException e) {
            throw new IOException(e);
        } catch (ExecutionException e) {
            throw new IOException(e);
        } catch (TimeoutException e) {
            throw new IOException(e);
        }
    }


    void startMessage(byte opCode, ByteBuffer payload, boolean last,
            SendHandler handler) {

        wsSession.updateLastActive();

        MessagePart mp = new MessagePart(opCode, payload, last, handler, this);

        synchronized (messagePartLock) {
            if (Constants.OPCODE_CLOSE == mp.getOpCode()) {
                try {
                    setBatchingAllowed(false);
                } catch (IOException e) {
                    log.warn(sm.getString(
                            "wsRemoteEndpoint.flushOnCloseFailed"), e);
                }
            }
            if (messagePartInProgress) {
                // When a control message is sent while another message is being
                // the control message is queued. Chances are the subsequent
                // data message part will end up queued while the control
                // message is sent. The logic in this class (state machine,
                // EndMessageHanlder, TextMessageSendHandler) ensures that there
                // will only ever be one data message part in the queue. There
                // could be multiple control messages in the queue.

                // Add it to the queue
                messagePartQueue.add(mp);
            } else {
                messagePartInProgress = true;
                writeMessagePart(mp);
            }
        }
    }


    void endMessage(SendHandler handler, SendResult result) {
        synchronized (messagePartLock) {

            fragmented = nextFragmented;
            text = nextText;

            MessagePart mpNext = messagePartQueue.poll();
            if (mpNext == null) {
                messagePartInProgress = false;
            } else if (!closed){
                // Session may have been closed unexpectedly in the middle of
                // sending a fragmented message closing the endpoint. If this
                // happens, clearly there is no point trying to send the rest of
                // the message.
                writeMessagePart(mpNext);
            }
        }

        wsSession.updateLastActive();

        handler.onResult(result);
    }


    void writeMessagePart(MessagePart mp) {

        if (closed) {
            throw new IllegalStateException(
                    sm.getString("wsRemoteEndpoint.closed"));
        }

        if (Constants.INTERNAL_OPCODE_FLUSH == mp.getOpCode()) {
            nextFragmented = fragmented;
            nextText = text;
            doWrite(mp.getHandler(), outputBuffer);
            return;
        }

        // Control messages may be sent in the middle of fragmented message
        // so they have no effect on the fragmented or text flags
        boolean first;
        if (Util.isControl(mp.getOpCode())) {
            nextFragmented = fragmented;
            nextText = text;
            if (mp.getOpCode() == Constants.OPCODE_CLOSE) {
                closed = true;
            }
            first = true;
        } else {
            boolean isText = Util.isText(mp.getOpCode());

            if (fragmented) {
                // Currently fragmented
                if (text != isText) {
                    throw new IllegalStateException(
                            sm.getString("wsRemoteEndpoint.changeType"));
                }
                nextText = text;
                nextFragmented = !mp.isLast();
                first = false;
            } else {
                // Wasn't fragmented. Might be now
                if (mp.isLast()) {
                    nextFragmented = false;
                } else {
                    nextFragmented = true;
                    nextText = isText;
                }
                first = true;
            }
        }

        byte[] mask;

        if (isMasked()) {
            mask = Util.generateMask();
        } else {
            mask = null;
        }

        headerBuffer.clear();
        writeHeader(headerBuffer, mp.getOpCode(), mp.getPayload(), first,
                mp.isLast(), isMasked(), mask);
        headerBuffer.flip();

        if (getBatchingAllowed() || isMasked()) {
            // Need to write via output buffer
            OutputBufferSendHandler obsh = new OutputBufferSendHandler(
                    mp.getHandler(), headerBuffer, mp.getPayload(), mask,
                    outputBuffer, !getBatchingAllowed(), this);
            obsh.write();
        } else {
            // Can write directly
            doWrite(mp.getHandler(), headerBuffer, mp.getPayload());
        }

    }


    private long getBlockingSendTimeout() {
        Object obj = wsSession.getUserProperties().get(
                BLOCKING_SEND_TIMEOUT_PROPERTY);
        Long userTimeout = null;
        if (obj instanceof Long) {
            userTimeout = (Long) obj;
        }
        if (userTimeout == null) {
            return DEFAULT_BLOCKING_SEND_TIMEOUT;
        } else {
            return userTimeout.longValue();
        }
    }


    private static class MessagePart {
        private final byte opCode;
        private final ByteBuffer payload;
        private final boolean last;
        private final SendHandler handler;

        public MessagePart(byte opCode, ByteBuffer payload, boolean last,
                SendHandler handler, WsRemoteEndpointImplBase endpoint) {
            this.opCode = opCode;
            this.payload = payload;
            this.last = last;
            this.handler = new EndMessageHandler(endpoint, handler);
        }


        public byte getOpCode() {
            return opCode;
        }


        public ByteBuffer getPayload() {
            return payload;
        }


        public boolean isLast() {
            return last;
        }


        public SendHandler getHandler() {
            return handler;
        }
    }


    /**
     * Wraps the user provided handler so that the end point is notified when
     * the message is complete.
     */
    private static class EndMessageHandler implements SendHandler {

        private final WsRemoteEndpointImplBase endpoint;
        private final SendHandler handler;

        public EndMessageHandler(WsRemoteEndpointImplBase endpoint,
                SendHandler handler) {
            this.endpoint = endpoint;
            this.handler = handler;
        }


        @Override
        public void onResult(SendResult result) {
            endpoint.endMessage(handler, result);
        }
    }


    public void sendObject(Object obj) throws IOException {
        Future<Void> f = sendObjectByFuture(obj);
        try {
            f.get();
        } catch (InterruptedException e) {
            throw new IOException(e);
        } catch (ExecutionException e) {
            throw new IOException(e);
        }
    }

    public Future<Void> sendObjectByFuture(Object obj) {
        FutureToSendHandler f2sh = new FutureToSendHandler(wsSession);
        sendObjectByCompletion(obj, f2sh);
        return f2sh;
    }


    @SuppressWarnings({"unchecked", "rawtypes"})
    public void sendObjectByCompletion(Object obj, SendHandler completion) {

        if (Util.isPrimitive(obj.getClass())) {
            String msg = obj.toString();
            sendStringByCompletion(msg, completion);
            return;
        }

        Encoder encoder = findEncoder(obj);

        try {
            if (encoder instanceof Encoder.Text) {
                String msg = ((Encoder.Text) encoder).encode(obj);
                sendStringByCompletion(msg, completion);
            } else if (encoder instanceof Encoder.TextStream) {
                Writer w = null;
                try {
                    w = getSendWriter();
                    ((Encoder.TextStream) encoder).encode(obj, w);
                } finally {
                    if (w != null) {
                        try {
                            w.close();
                        } catch (IOException ioe) {
                            // Ignore
                        }
                    }
                }
                completion.onResult(new SendResult());
            } else if (encoder instanceof Encoder.Binary) {
                ByteBuffer msg = ((Encoder.Binary) encoder).encode(obj);
                sendBytesByCompletion(msg, completion);
            } else if (encoder instanceof Encoder.BinaryStream) {
                OutputStream os = null;
                try {
                    os = getSendStream();
                    ((Encoder.BinaryStream) encoder).encode(obj, os);
                } finally {
                    if (os != null) {
                        try {
                            os.close();
                        } catch (IOException ioe) {
                            // Ignore
                        }
                    }
                }
                completion.onResult(new SendResult());
            } else {
                throw new EncodeException(obj, sm.getString(
                        "wsRemoteEndpoint.noEncoder", obj.getClass()));
            }
        } catch (EncodeException e) {
            SendResult sr = new SendResult(e);
            completion.onResult(sr);
        } catch (IOException e) {
            SendResult sr = new SendResult(e);
            completion.onResult(sr);
        }
    }


    protected void setSession(WsSession wsSession) {
        this.wsSession = wsSession;
    }


    protected void setEncoders(EndpointConfig endpointConfig)
            throws DeploymentException {
        encoderEntries.clear();
        for (Class<? extends Encoder> encoderClazz :
                endpointConfig.getEncoders()) {
            Encoder instance;
            try {
                instance = encoderClazz.newInstance();
                instance.init(endpointConfig);
            } catch (InstantiationException e) {
                throw new DeploymentException(
                        sm.getString("wsRemoteEndpoint.invalidEncoder",
                                encoderClazz.getName()), e);
            } catch (IllegalAccessException e) {
                throw new DeploymentException(
                        sm.getString("wsRemoteEndpoint.invalidEncoder",
                                encoderClazz.getName()), e);
            }
            EncoderEntry entry = new EncoderEntry(
                    Util.getEncoderType(encoderClazz), instance);
            encoderEntries.add(entry);
        }
    }


    private Encoder findEncoder(Object obj) {
        for (EncoderEntry entry : encoderEntries) {
            if (entry.getClazz().isAssignableFrom(obj.getClass())) {
                return entry.getEncoder();
            }
        }
        return null;
    }


    public final void close() {
        for (EncoderEntry entry : encoderEntries) {
            entry.getEncoder().destroy();
        }
        doClose();
    }


    protected abstract void doWrite(SendHandler handler, ByteBuffer... data);
    protected abstract boolean isMasked();
    protected abstract void doClose();

    private static void writeHeader(ByteBuffer headerBuffer, byte opCode,
            ByteBuffer payload, boolean first, boolean last, boolean masked,
            byte[] mask) {

        byte b = 0;

        if (last) {
            // Set the fin bit
            b = -128;
        }

        if (first) {
            // This is the first fragment of this message
            b = (byte) (b + opCode);
        }
        // If not the first fragment, it is a continuation with opCode of zero

        headerBuffer.put(b);

        if (masked) {
            b = (byte) 0x80;
        } else {
            b = 0;
        }

        // Next write the mask && length length
        if (payload.limit() < 126) {
            headerBuffer.put((byte) (payload.limit() | b));
        } else if (payload.limit() < 65536) {
            headerBuffer.put((byte) (126 | b));
            headerBuffer.put((byte) (payload.limit() >>> 8));
            headerBuffer.put((byte) (payload.limit() & 0xFF));
        } else {
            // Will never be more than 2^31-1
            headerBuffer.put((byte) (127 | b));
            headerBuffer.put((byte) 0);
            headerBuffer.put((byte) 0);
            headerBuffer.put((byte) 0);
            headerBuffer.put((byte) 0);
            headerBuffer.put((byte) (payload.limit() >>> 24));
            headerBuffer.put((byte) (payload.limit() >>> 16));
            headerBuffer.put((byte) (payload.limit() >>> 8));
            headerBuffer.put((byte) (payload.limit() & 0xFF));
        }
        if (masked) {
            headerBuffer.put(mask[0]);
            headerBuffer.put(mask[1]);
            headerBuffer.put(mask[2]);
            headerBuffer.put(mask[3]);
        }
    }


    private class TextMessageSendHandler implements SendHandler {

        private final SendHandler handler;
        private final CharBuffer message;
        private final boolean isLast;
        private final CharsetEncoder encoder;
        private final ByteBuffer buffer;
        private final WsRemoteEndpointImplBase endpoint;
        private volatile boolean isDone = false;

        public TextMessageSendHandler(SendHandler handler, CharBuffer message,
                boolean isLast, CharsetEncoder encoder,
                ByteBuffer encoderBuffer, WsRemoteEndpointImplBase endpoint) {
            this.handler = handler;
            this.message = message;
            this.isLast = isLast;
            this.encoder = encoder.reset();
            this.buffer = encoderBuffer;
            this.endpoint = endpoint;
        }

        public void write() {
            buffer.clear();
            CoderResult cr = encoder.encode(message, buffer, true);
            if (cr.isError()) {
                throw new IllegalArgumentException(cr.toString());
            }
            isDone = !cr.isOverflow();
            buffer.flip();
            endpoint.startMessage(Constants.OPCODE_TEXT, buffer,
                    isDone && isLast, this);
        }

        @Override
        public void onResult(SendResult result) {
            if (isDone) {
                endpoint.stateMachine.complete(isLast);
                handler.onResult(result);
            } else if(!result.isOK()) {
                handler.onResult(result);
            } else if (closed){
                SendResult sr = new SendResult(new IOException(
                        sm.getString("wsRemoteEndpoint.closedDuringMessage")));
                handler.onResult(sr);
            } else {
                write();
            }
        }
    }


    /**
     * Used to write data to the output buffer, flushing the buffer if it fills
     * up.
     */
    private static class OutputBufferSendHandler implements SendHandler {

        private final SendHandler handler;
        private final ByteBuffer headerBuffer;
        private final ByteBuffer payload;
        private final byte[] mask;
        private final ByteBuffer outputBuffer;
        private final boolean flushRequired;
        private final WsRemoteEndpointImplBase endpoint;
        private int maskIndex = 0;

        public OutputBufferSendHandler(SendHandler completion,
                ByteBuffer headerBuffer, ByteBuffer payload, byte[] mask,
                ByteBuffer outputBuffer, boolean flushRequired,
                WsRemoteEndpointImplBase endpoint) {
            this.handler = completion;
            this.headerBuffer = headerBuffer;
            this.payload = payload;
            this.mask = mask;
            this.outputBuffer = outputBuffer;
            this.flushRequired = flushRequired;
            this.endpoint = endpoint;
        }

        public void write() {
            // Write the header
            while (headerBuffer.hasRemaining() && outputBuffer.hasRemaining()) {
                outputBuffer.put(headerBuffer.get());
            }
            if (headerBuffer.hasRemaining()) {
                // Still more headers to write, need to flush
                outputBuffer.flip();
                endpoint.doWrite(this, outputBuffer);
                return;
            }

            // Write the payload
            int payloadLeft = payload.remaining();
            int payloadLimit = payload.limit();
            int outputSpace = outputBuffer.remaining();
            int toWrite = payloadLeft;

            if (payloadLeft > outputSpace) {
                toWrite = outputSpace;
                // Temporarily reduce the limit
                payload.limit(payload.position() + toWrite);
            }

            if (mask == null) {
                // Use a bulk copy
                outputBuffer.put(payload);
            } else {
                for (int i = 0; i < toWrite; i++) {
                    outputBuffer.put(
                            (byte) (payload.get() ^ (mask[maskIndex++] & 0xFF)));
                    if (maskIndex > 3) {
                        maskIndex = 0;
                    }
                }
            }

            if (payloadLeft > outputSpace) {
                // Restore the original limit
                payload.limit(payloadLimit);
                // Still more headers to write, need to flush
                outputBuffer.flip();
                endpoint.doWrite(this, outputBuffer);
                return;
            }

            if (flushRequired) {
                outputBuffer.flip();
                if (outputBuffer.remaining() == 0) {
                    handler.onResult(new SendResult());
                } else {
                    endpoint.doWrite(this, outputBuffer);
                }
            } else {
                handler.onResult(new SendResult());
            }
        }

        // ------------------------------------------------- SendHandler methods
        @Override
        public void onResult(SendResult result) {
            if (result.isOK()) {
                if (outputBuffer.hasRemaining()) {
                    endpoint.doWrite(this, outputBuffer);
                } else {
                    outputBuffer.clear();
                    write();
                }
            } else {
                handler.onResult(result);
            }
        }
    }

    private class WsOutputStream extends OutputStream {

        private final WsRemoteEndpointImplBase endpoint;
        private final ByteBuffer buffer = ByteBuffer.allocate(8192);
        private final Object closeLock = new Object();
        private volatile boolean closed = false;

        public WsOutputStream(WsRemoteEndpointImplBase endpoint) {
            this.endpoint = endpoint;
        }

        @Override
        public void write(int b) throws IOException {
            if (closed) {
                throw new IllegalStateException(
                        sm.getString("wsRemoteEndpoint.closedOutputStream"));
            }

            if (buffer.remaining() == 0) {
                flush();
            }
            buffer.put((byte) b);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            if (closed) {
                throw new IllegalStateException(
                        sm.getString("wsRemoteEndpoint.closedOutputStream"));
            }
            if (len == 0) {
                return;
            }
            if ((off < 0) || (off > b.length) || (len < 0) ||
                ((off + len) > b.length) || ((off + len) < 0)) {
                throw new IndexOutOfBoundsException();
            }

            if (buffer.remaining() == 0) {
                flush();
            }
            int remaining = buffer.remaining();
            int written = 0;

            while (remaining < len - written) {
                buffer.put(b, off + written, remaining);
                written += remaining;
                flush();
                remaining = buffer.remaining();
            }
            buffer.put(b, off + written, len - written);
        }

        @Override
        public void flush() throws IOException {
            if (closed) {
                throw new IllegalStateException(
                        sm.getString("wsRemoteEndpoint.closedOutputStream"));
            }

            doWrite(false);
        }

        @Override
        public void close() throws IOException {
            synchronized (closeLock) {
                if (closed) {
                    return;
                }
                closed = true;
            }

            doWrite(true);
        }

        private void doWrite(boolean last) throws IOException {
            buffer.flip();
            endpoint.startMessageBlock(Constants.OPCODE_BINARY, buffer, last);
            stateMachine.complete(last);
            buffer.clear();
        }
    }


    private static class WsWriter extends Writer {

        private final WsRemoteEndpointImplBase endpoint;
        private final CharBuffer buffer = CharBuffer.allocate(8192);
        private final Object closeLock = new Object();
        private volatile boolean closed = false;

        public WsWriter(WsRemoteEndpointImplBase endpoint) {
            this.endpoint = endpoint;
        }

        @Override
        public void write(char[] cbuf, int off, int len) throws IOException {
            if (closed) {
                throw new IllegalStateException(
                        sm.getString("wsRemoteEndpoint.closedWriter"));
            }
            if (len == 0) {
                return;
            }
            if ((off < 0) || (off > cbuf.length) || (len < 0) ||
                    ((off + len) > cbuf.length) || ((off + len) < 0)) {
                throw new IndexOutOfBoundsException();
            }

            if (buffer.remaining() == 0) {
                flush();
            }
            int remaining = buffer.remaining();
            int written = 0;

            while (remaining < len - written) {
                buffer.put(cbuf, off + written, remaining);
                written += remaining;
                flush();
                remaining = buffer.remaining();
            }
            buffer.put(cbuf, off + written, len - written);
        }

        @Override
        public void flush() throws IOException {
            if (closed) {
                throw new IllegalStateException(
                        sm.getString("wsRemoteEndpoint.closedWriter"));
            }

            doWrite(false);
        }

        @Override
        public void close() throws IOException {
            synchronized (closeLock) {
                if (closed) {
                    return;
                }
                closed = true;
            }

            doWrite(true);
        }

        private void doWrite(boolean last) throws IOException {
            buffer.flip();
            endpoint.sendPartialString(buffer, last);
            buffer.clear();
        }
    }


    private static class EncoderEntry {

        private final Class<?> clazz;
        private final Encoder encoder;

        public EncoderEntry(Class<?> clazz, Encoder encoder) {
            this.clazz = clazz;
            this.encoder = encoder;
        }

        public Class<?> getClazz() {
            return clazz;
        }

        public Encoder getEncoder() {
            return encoder;
        }
    }


    private static enum State {
        OPEN,
        STREAM_WRITING,
        WRITER_WRITING,
        BINARY_PARTIAL_WRITING,
        BINARY_PARTIAL_READY,
        BINARY_FULL_WRITING,
        TEXT_PARTIAL_WRITING,
        TEXT_PARTIAL_READY,
        TEXT_FULL_WRITING
    }


    private static class StateMachine {
        private State state = State.OPEN;

        public synchronized void streamStart() {
            checkState(State.OPEN);
            state = State.STREAM_WRITING;
        }

        public synchronized void writeStart() {
            checkState(State.OPEN);
            state = State.WRITER_WRITING;
        }

        public synchronized void binaryPartialStart() {
            checkState(State.OPEN, State.BINARY_PARTIAL_READY);
            state = State.BINARY_PARTIAL_WRITING;
        }

        public synchronized void binaryStart() {
            checkState(State.OPEN);
            state = State.BINARY_FULL_WRITING;
        }

        public synchronized void textPartialStart() {
            checkState(State.OPEN, State.TEXT_PARTIAL_READY);
            state = State.TEXT_PARTIAL_WRITING;
        }

        public synchronized void textStart() {
            checkState(State.OPEN);
            state = State.TEXT_FULL_WRITING;
        }

        public synchronized void complete(boolean last) {
            if (last) {
                checkState(State.TEXT_PARTIAL_WRITING, State.TEXT_FULL_WRITING,
                        State.BINARY_PARTIAL_WRITING, State.BINARY_FULL_WRITING,
                        State.STREAM_WRITING, State.WRITER_WRITING);
                state = State.OPEN;
            } else {
                checkState(State.TEXT_PARTIAL_WRITING, State.BINARY_PARTIAL_WRITING,
                        State.STREAM_WRITING, State.WRITER_WRITING);
                if (state == State.TEXT_PARTIAL_WRITING) {
                    state = State.TEXT_PARTIAL_READY;
                } else if (state == State.BINARY_PARTIAL_WRITING){
                    state = State.BINARY_PARTIAL_READY;
                } else if (state == State.WRITER_WRITING) {
                    // NO-OP. Leave state as is.
                } else if (state == State.STREAM_WRITING) {
                 // NO-OP. Leave state as is.
                } else {
                    // Should never happen
                    // The if ... else ... blocks above should cover all states
                    // permitted by the preceding checkState() call
                    throw new IllegalStateException(
                            "BUG: This code should never be called");
                }
            }
        }

        private void checkState(State... required) {
            for (State state : required) {
                if (this.state == state) {
                    return;
                }
            }
            throw new IllegalStateException(
                    sm.getString("wsRemoteEndpoint.wrongState", this.state));
        }
    }


    private class StateUpdateSendHandler implements SendHandler {

        private final SendHandler handler;

        public StateUpdateSendHandler(SendHandler handler) {
            this.handler = handler;
        }

        @Override
        public void onResult(SendResult result) {
            if (result.isOK()) {
                stateMachine.complete(true);
            }
            handler.onResult(result);
        }
    }
}
