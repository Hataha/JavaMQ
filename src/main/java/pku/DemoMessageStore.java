package pku;

import java.io.*;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.*;

/**
 * 这是一个消息队列的内存实现
 */
public class DemoMessageStore {
	static final DemoMessageStore store = new DemoMessageStore();

	HashMap<String, DataOutputStream> outMap = new HashMap<>();
    HashMap<String, DataInputStream> inMap  = new HashMap<>();
    HashMap<String, MappedByteBuffer> mbbMap  = new HashMap<>();

    DataOutputStream out;   // 按 topic 写入不同 topic 文件
    DataInputStream in; // 按 queue + topic 读取 不同 topic 文件
    MappedByteBuffer inMbb;

	// 加锁保证线程安全
	/**
	 * @param msg
	 * @param topic
	 */
	public synchronized void push(ByteMessage msg, String topic) {
		if (msg == null)
			return;

        try {

            if (!outMap.containsKey(topic)) {
                File file = new File("./data/" + topic);
                if (file.exists()) file.delete();

                outMap.put(topic, new DataOutputStream(new BufferedOutputStream(
                        new FileOutputStream("./data/" + topic,  true))));
            }
            out = outMap.get(topic);


            out.writeByte(msg.headers().getMap().size());


            // write headers' keyLength, keyBytes, valueLength, valueBytes
            for (Map.Entry<String, Object> entry : msg.headers().getMap().entrySet()) {
                String headerKey = entry.getKey();

                out.writeByte(headerKey.getBytes().length); // kLen
                out.write(headerKey.getBytes());

                // headerType: 0 int, 1 long, 2 double, 3 string
                int headerType = MessageHeader.getHeaderType(headerKey);
                if (headerType == 0) {
                    //out.writeByte(4); // 知道int数据类型长度，不需要存
                    out.writeInt((int) entry.getValue());
                } else if (headerType == 1) {
                    //out.writeByte(8);
                    out.writeLong((long) entry.getValue());
                } else if (headerType == 2) {
                    //out.writeByte(8);
                    out.writeDouble((double) entry.getValue());
                } else if (headerType == 3){
                    String strVal = (String) entry.getValue();
                    out.writeByte(strVal.getBytes().length);
                    out.write(strVal.getBytes());
                } else {
                    System.out.println("key 类型不对");
                }
            }

            // write body's length, byte[]
            int bodyLen = msg.getBody().length;
            if (bodyLen <= 127) {
                out.writeByte(0);
                out.writeByte(bodyLen);
            } else {
                out.writeByte(-1);  // body[] 的长度 > 127，即超过byte，先存入 -1 ，再存入用int表示的长度
                out.writeInt(bodyLen);
            }
            out.write(msg.getBody());

        } catch (IOException e) {
            e.printStackTrace();
        }

	}

	// 加锁保证线程安全
	public synchronized ByteMessage pull(String queue, String topic) {
        try {

            if (! new File("./data/" + topic).exists()) // 不存在此 topic 文件
                return null;

            String key = queue + topic;
            if (!inMap.containsKey(key)) {
                inMap.put(key, new DataInputStream(new BufferedInputStream(new FileInputStream("./data/" + topic))));
            }
            //每个 queue+topic 都有一个InputStream
            in = inMap.get(key);

            // 此 topic 已读取完毕
            if (in.available() == 0) {
                inMap.remove(key);
                return null;
            }

            // 读取 headers 部分
            KeyValue headers = new DefaultKeyValue();
            int headerSize = in.readByte();
            for (int i = 0; i < headerSize; i++) {
                byte kLen = in.readByte();    // keyLength
                byte[] bytes = new byte[kLen];
                in.read(bytes);
                String headerKey = new String(bytes);   // key

                // 0 int, 1 long, 2 double, 3 string
                // System.out.println(headerKey);
                int headerType = MessageHeader.getHeaderType(headerKey);

                if (headerType == 0) {
                    headers.put(headerKey, in.readInt());
                } else if (headerType == 1) {
                    headers.put(headerKey, in.readLong());
                } else if (headerType == 2) {
                    headers.put(headerKey, in.readDouble());
                } else if (headerType == 3){
                    byte vLen = in.readByte();    // valueLength
                    byte[] vals = new byte[vLen];    // value
                    in.read(vals);
                    headers.put(headerKey, new String(vals));
                } else {
                    System.out.println("key 类型不对");
                }
            }

            // 读取 body 部分
            byte isByte = in.readByte();
            byte[] body;
            if (isByte == 0) {
                body = new byte[in.readByte()];
            } else {
                body = new byte[in.readInt()];
            }
            in.read(body);

            /*
            byte check = in.readByte();
            if (check != -1) {
                System.out.println(headerSize);
                System.out.println(Arrays.toString(headers.getMap().entrySet().toArray()));
                System.out.println(bodyLen);
                System.out.println(Arrays.toString(body));
                System.out.println(check);
                System.out.println(cnt);
            }

            cnt++;*/


            // 组成消息并返回
            ByteMessage msg = new DefaultMessage(body);
            msg.setHeaders(headers);
            return msg;

        } catch (IOException e) {
            e.printStackTrace();
        }
        return null;
	}



    // 加锁保证线程安全
    public synchronized ByteMessage pullMBB(String queue, String topic) {
        try {

            if (! new File("./data/" + topic).exists()) // 不存在此 topic 文件
                return null;

            String key = queue + topic;
            if (!mbbMap.containsKey(key)) {

                RandomAccessFile rafi = new RandomAccessFile("./data/" + topic, "r");
                FileChannel fci = rafi.getChannel();
                MappedByteBuffer inMbb = fci.map(FileChannel.MapMode.READ_ONLY, 0, fci.size());

                mbbMap.put(key, inMbb);
            }
            inMbb = mbbMap.get(key);

            // 这个流已经读完
            if (!inMbb.hasRemaining()) {
                mbbMap.remove(key);
                // System.out.println(key);
                return null;
            }

            // 读取 headers 部分
            KeyValue headers = new DefaultKeyValue();
            int headerSize = inMbb.get();
            for (int i = 0; i < headerSize; i++) {
                byte kLen = inMbb.get();    // keyLength
                byte[] bytes = new byte[kLen];
                inMbb.get(bytes);
                String headerKey = new String(bytes);   // key

                // 0 int, 1 long, 2 double, 3 string
                // System.out.println(headerKey);
                int headerType = MessageHeader.getHeaderType(headerKey);

                if (headerType == 0) {
                    headers.put(headerKey, inMbb.getInt());
                } else if (headerType == 1) {
                    headers.put(headerKey, inMbb.getLong());
                } else if (headerType == 2) {
                    headers.put(headerKey, inMbb.getDouble());
                } else {
                    byte vLen = inMbb.get();    // valueLength
                    byte[] vals = new byte[vLen];    // value
                    inMbb.get(vals);
                    headers.put(headerKey, new String(vals));
                }
            }



            // 读取 body 部分
            byte isByte = inMbb.get();
            byte[] body;
            if (isByte == 0) {
                body = new byte[inMbb.get()];
            } else {
                body = new byte[inMbb.getInt()];
            }
            inMbb.get(body);

            // 组成消息并返回
            ByteMessage msg = new DefaultMessage(body);
            msg.setHeaders(headers);
            return msg;

        } catch (IOException e) {
            e.printStackTrace();
            return null;
        }
    }


    // flush
	public void flush(Set<String> topics) {
        DataOutputStream out;
        try {
            for (String topic : topics) {
                out = outMap.get(topic);
                out.flush();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
