package pku;

import java.io.*;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * 这是一个消息队列的内存实现
 */
public class DemoMessageStore {
	static final DemoMessageStore store = new DemoMessageStore();

	HashMap<String, DataOutputStream> outMap = new HashMap<>();
    HashMap<String, DataInputStream> inMap  = new HashMap<>();

    DataOutputStream out;   // 按 topic 写入不同 topic 文件
    DataInputStream in; // 按 queue + topic 读取 不同 topic 文件

	// 加锁保证线程安全
	/**
	 * @param msg
	 * @param topic
	 */
	public synchronized void push(ByteMessage msg, String topic) {
		if (msg == null) {
			return;
		}

        try {
            if (!outMap.containsKey(topic)) {
                File file = new File("./data/" + topic);
                if (file.exists()) file.delete();

                outMap.put(topic, new DataOutputStream(new BufferedOutputStream(
                        new FileOutputStream("./data/" + topic,  true))));
            }
            out = outMap.get(topic);

            out.writeByte((byte) msg.headers().getMap().size());

            // write headers' keyLength, keyBytes, valueLength, valueBytes
            for (Map.Entry<String, Object> entry : msg.headers().getMap().entrySet()) {
                String headerKey = entry.getKey();

                out.writeByte(headerKey.getBytes().length);
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
                } else {
                    String strVal = (String) entry.getValue();
                    out.writeByte((byte) strVal.getBytes().length);
                    out.write(strVal.getBytes());
                }
            }

            // write body's length, byte[]
            out.writeByte((byte) msg.getBody().length);
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
                inMap.put(key, new DataInputStream(new BufferedInputStream(
                        new FileInputStream("./data/" + topic))));
            }
            //每个 queue+topic 都有一个InputStream
            in = inMap.get(key);

            if (in.available() == 0) {
                return null;
            }

            int headerSize = in.readByte();

            // 读取 headers 部分
            KeyValue headers = new DefaultKeyValue();
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
                } else {
                    byte vLen = in.readByte();    // valueLength
                    byte[] vals = new byte[vLen];    // value
                    in.read(vals);
                    headers.put(headerKey, new String(vals));
                }
            }

            // 读取 body 部分
            byte bodyLen = in.readByte();
            byte[] body = new byte[bodyLen];
            in.read(body);

            // 组成消息并返回
            ByteMessage msg = new DefaultMessage(body);
            msg.setHeaders(headers);
            return msg;

        } catch (IOException e) {
            e.printStackTrace();
        }
        return null;
	}


	public void flush(Set<String> topics) {
        DataOutputStream out;
        for (String topic : topics) {
            out = outMap.get(topic);
            try {
                out.flush();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }
}
