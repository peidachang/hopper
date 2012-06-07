package com.hopper.session;

import com.hopper.verb.Verb;
import com.hopper.verb.handler.VerbMappings;
import com.hopper.utils.ByteUtils;

import java.io.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * {@link Message} is designed as the communication unit between endpoints.
 * {@link Message} represents the communication "header", concrete contents are
 * encapsulated into <tt>{@link #body}</tt> filed, only the implementations can
 * understand the <tt>{@link #body}</tt>.
 * <p/>
 * Message will be delivered to one session, indicating with
 * <tt>{@link #sessionId}</tt>, the session can be local session or multiplexer
 * session.
 *
 * @author chenguoqing
 */
public class Message {
    /**
     * ID
     */
    private static final AtomicInteger ID = new AtomicInteger();

    /**
     * Message type: request
     */
    public static final int REQUEST = 0;
    /**
     * Message type : response
     */
    public static final int RESPONSE = 1;
    /**
     * Message id
     */
    private int id;
    /**
     * Message verb
     */
    private Verb verb;
    /**
     * The associated session id
     */
    private String sessionId;
    /**
     * The associated command
     */
    private Object body;

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public Verb getVerb() {
        return verb;
    }

    public void setVerb(Verb verb) {
        this.verb = verb;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public Object getBody() {
        return body;
    }

    public void setBody(Object body) {
        this.body = body;
    }

    public static int nextId() {
        return ID.incrementAndGet();
    }

    /**
     * Serialize the request object to byte array. If the <tt>body</tt> is the
     * instance of {@link Serializer},invoks the
     * {@link Serializer#serialize(DataOutput)} for serializing; otherwise, if
     * <tt>body</tt> is a byte array instance, writes it directly to stream.
     */
    public byte[] serialize() {

        ByteArrayOutputStream bout = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(bout);

        try {
            dos.writeInt(id);
            dos.writeInt(verb.type);
            writeUTF(dos, sessionId);

            if (body instanceof Serializer) {
                ((Serializer) body).serialize(dos);
            } else if (body instanceof byte[]) {
                dos.write((byte[]) body);
            }
        } catch (IOException e) {
            // nothing
        }

        int contentSize = bout.size();

        byte[] buf = new byte[4 + contentSize];

        ByteUtils.int2Bytes(contentSize, buf, 0);

        bout.write(buf, 4, contentSize);

        return buf;
    }

    /**
     * Deserialize the bytes to message instance. If body's class is supported,
     * using it for deserializing; otherwise, setting the byte array as the
     * body.
     */
    public void deserialize(byte[] buf) {
        ByteArrayInputStream bis = new ByteArrayInputStream(buf);
        DataInputStream in = new DataInputStream(bis);

        try {
            this.id = in.readInt();
            int iverb = in.readInt();
            this.verb = Verb.getVerb(iverb);

            if (verb == null) {
                throw new MessageDecodeException("Found the invalidate verb: " + iverb);
            }

            this.sessionId = readUTF(in);

            Class<? extends Serializer> bodyClazz = VerbMappings.getVerClass(verb);

            if (bodyClazz != null) {
                this.body = deserializeBody(in, bodyClazz);
            } else {
                int remaining = bis.available();
                if (remaining > 0) {
                    this.body = new byte[remaining];
                    bis.read((byte[]) body);
                }
            }
        } catch (Exception e) {
            throw new MessageDecodeException("Failed to deserialize the message.", e);
        }
    }

    /**
     * Deserialize the {@link DataInput} to the special instance.
     */
    private Object deserializeBody(DataInput in, Class<? extends Serializer> bodyClazz) throws Exception {
        Serializer instance = bodyClazz.newInstance();
        instance.deserialize(in);
        return instance;
    }

    /**
     * Wrapper the writeUTF method
     */
    private void writeUTF(DataOutput out, String s) throws IOException {
        out.writeUTF(s == null ? "null" : sessionId);
    }

    /**
     * Wrapper readUTF method
     */
    private String readUTF(DataInput in) throws IOException {
        String s = in.readUTF();
        return "null".equals(s) ? null : s;
    }
}