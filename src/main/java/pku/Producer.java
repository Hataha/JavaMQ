package pku;

import java.io.*;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.zip.DeflaterOutputStream;

/**
 * 生产者: 依次遍历 topics 每个 topic 生产 PUSH_COUNT 个消息
 */
public class Producer {

    private Set<String> topics = new HashSet<>();
    static int count = 4;
    // static HashMap<String, Long> cnt = new HashMap<>();

    DataOutputStream out;   // 按 topic 写入不同 topic 文件

    private static final String FILE_DIR = "./data/";
    private static final int ONE_WRITE_SIZE = 400;
    private static final HashMap<String, BufferedOutputStream> outMap = new HashMap<>();    // topics' outstream


    byte[] array = new byte[(ONE_WRITE_SIZE << 8)];  // 2560000
    private ByteBuffer buffer = ByteBuffer.wrap(array);
    ByteMessage[] msgs = new ByteMessage[ONE_WRITE_SIZE];
    int index = 0;
    BufferedOutputStream fileChannel = null;
    static int maxIndex = -1;   // 800-185206   10000-2250883



	// 生成一个指定topic的message返回
    public ByteMessage createBytesMessageToTopic(String topic, byte[] body) {
        ByteMessage msg = new DefaultMessage(body);
        msg.putHeaders(MessageHeader.TOPIC, topic);
        return msg;
    }

    //将message发送出去
    public void send(ByteMessage message) {
        // String topic = msg.headers().getString(MessageHeader.TOPIC);

        // DemoMessageStore.store.push(header, msg.getBody(), topic);
        msgs[index++] = message;
        if (index >= ONE_WRITE_SIZE) {

            for (ByteMessage msg : msgs) {
                buffer.put(getHeaderBytes(msg));

                int bodyLen = msg.getBody().length;
                if (bodyLen <= Byte.MAX_VALUE) {    // body[] 的长度 > 127，即超过byte，先存入 1 ，再存入用int表示的长度
                    buffer.put((byte) 0);
                    buffer.put((byte) bodyLen);
                    buffer.put(msg.getBody());
                } else {
                    buffer.put((byte) 1);
                    byte[] compressedBody = compress(msg.getBody());
                    buffer.putInt(compressedBody.length);
                    buffer.put(compressedBody);
                }
            }
            buffer.flip();

            writeMsgs(message.headers().getString(MessageHeader.TOPIC));

            buffer.clear();
            index = 0;
        }
    }


    public synchronized void writeMsgs(String topic) {
        try {


            //synchronized (topicStreams) {
            fileChannel = outMap.get(topic);
            if (fileChannel == null) {
                File file = new File(FILE_DIR + topic);
                if (file.exists()) file.delete();

                fileChannel = new BufferedOutputStream(new FileOutputStream(file, true));
                outMap.put(topic, fileChannel);
            }
            maxIndex = Math.max(maxIndex, buffer.remaining());
            fileChannel.write(array, 0, buffer.remaining());
            fileChannel.flush();
            //}




        } catch (IOException e) {
            e.printStackTrace();
        }
    }



    private byte[] getHeaderBytes(ByteMessage msg) {

        // use short to record header keys, except TOPIC
        KeyValue headers = msg.headers();
        short key = 0;

        int len = 2;    // short key
        for (int i = 14; i >= 0; i--) {
            key <<= 1;
            if (headers.containsKey(MessageHeader.headerKeys[i])) {
                key = (short) (key | 1);
                if (i < 4) len += 4;
                else if (i < 10) len += 8;
                else {
                    len += headers.getString(MessageHeader.headerKeys[i]).getBytes().length + 1;
                }
            }
        }

        byte[] header = new byte[len];
        header[0] = (byte) ((key >>> 8) & 0xFF);   // key
        header[1] = (byte) (key & 0xFF);

        int index = 2;
        int numInt;
        long numLong;
        byte[] strVals;
        for (int i = 0; i < 15; i++) {
            if ((key & 1) == 1) {
                if (i < 4) {
                    numInt = headers.getInt(MessageHeader.headerKeys[i]);
                    header[index++] = (byte) ((numInt >>> 24) & 0xFF);
                    header[index++] = (byte) ((numInt >>> 16) & 0xFF);
                    header[index++] = (byte) ((numInt >>> 8) & 0xFF);
                    header[index++] = (byte) (numInt & 0xFF);
                } else if (i < 8) {
                    numLong = headers.getLong(MessageHeader.headerKeys[i]);
                    header[index++] = (byte) ((numLong >>> 56) & 0xFF);
                    header[index++] = (byte) ((numLong >>> 48) & 0xFF);
                    header[index++] = (byte) ((numLong >>> 40) & 0xFF);
                    header[index++] = (byte) ((numLong >>> 32) & 0xFF);
                    header[index++] = (byte) ((numLong >>> 24) & 0xFF);
                    header[index++] = (byte) ((numLong >>> 16) & 0xFF);
                    header[index++] = (byte) ((numLong >>> 8) & 0xFF);
                    header[index++] = (byte) (numLong & 0xFF);
                } else if (i < 10) {
                    numLong = Double.doubleToLongBits(headers.getDouble(MessageHeader.headerKeys[i]));
                    header[index++] = (byte) ((numLong >>> 56) & 0xFF);
                    header[index++] = (byte) ((numLong >>> 48) & 0xFF);
                    header[index++] = (byte) ((numLong >>> 40) & 0xFF);
                    header[index++] = (byte) ((numLong >>> 32) & 0xFF);
                    header[index++] = (byte) ((numLong >>> 24) & 0xFF);
                    header[index++] = (byte) ((numLong >>> 16) & 0xFF);
                    header[index++] = (byte) ((numLong >>> 8) & 0xFF);
                    header[index++] = (byte) (numLong & 0xFF);
                } else {
                    strVals = headers.getString(MessageHeader.headerKeys[i]).getBytes();
                    header[index++] = (byte) strVals.length;
                    System.arraycopy(strVals, 0, header, index, strVals.length);
                    index += strVals.length;
                }

            }
            key >>= 1;
        }

        return header;
    }



    // 加锁保证线程安全
    public synchronized void push(byte[] header, byte[] body, String topic) {

        try {

            // 获取写入流
            // out = outMap.get(topic);
            if (!topics.contains(topic)) {
                File file = new File(FILE_DIR + topic);
                if (file.exists()) file.delete();

                if (out != null) out.flush();
                out = new DataOutputStream(new BufferedOutputStream(
                        new FileOutputStream(file, true), 32768));
                //outMap.put(topic, out);
                topics.add(topic);
            }


            out.write(header);

            int bodyLen = body.length;
            if (bodyLen <= Byte.MAX_VALUE) {    // body[] 的长度 > 127，即超过byte，先存入 1 ，再存入用int表示的长度
                out.writeByte(0);
                out.writeByte(bodyLen);
            } else {
                out.writeByte(1);
                out.writeInt(bodyLen);
            }

            out.write(body);



        } catch (IOException e) {
            e.printStackTrace();
        }
    }



    //处理将缓存区的剩余部分
    public void flush()throws Exception {
        /*
        if (--count == 0) {
            DemoMessageStore.store.flush();
            System.out.println("flush");
        }*/
        System.out.println(maxIndex);
        System.out.println("flush");
    }

    public static byte[] compress(byte[] in) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            DeflaterOutputStream defl = new DeflaterOutputStream(out);
            defl.write(in);
            defl.flush();
            defl.close();

            return out.toByteArray();
        } catch (Exception e) {
            e.printStackTrace();
            System.exit(150);
            return null;
        }
    }
}
