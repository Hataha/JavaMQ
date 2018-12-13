package pku;

import java.io.*;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.HashMap;
import java.util.Set;

/**
 * 这是一个消息队列的内存实现
 */
public class DemoMessageStore {
	static final DemoMessageStore store = new DemoMessageStore();

	HashMap<String, DataOutputStream> outMap = new HashMap<>();
    HashMap<String, MappedByteBuffer> inMap  = new HashMap<>();

    DataOutputStream out;   // 按 topic 写入不同 topic 文件
    MappedByteBuffer in;     // 按 queue + topic 读取 不同 topic 文件

    private static final String FILE_DIR = "./data/";

	// 加锁保证线程安全
	/**
	 * @param msg
	 * @param topic
	 */
	public synchronized void push(ByteMessage msg, String topic) {
		if (msg == null)
			return;

        try {
            // 获取写入流
            if (!outMap.containsKey(topic)) {
                File file = new File("./data/" + topic);
                if (file.exists()) file.delete();

                outMap.put(topic, new DataOutputStream(new BufferedOutputStream(
                        new FileOutputStream(FILE_DIR + topic,  true))));
            }
            out = outMap.get(topic);


            // use short to record header keys, except TOPIC
            KeyValue headers = msg.headers();
            short key = 0;
            for (int i = 0; i < 15; i++) {
                key = (short) (key << 1);
                if (headers.containsKey(MessageHeader.getHeader(14 - i)))
                    key = (short) (key | 1);
            }
            out.writeShort(key);
            for (int i = 0; i < 4; i++) {
                if ((key >> i & 1) == 1)
                    out.writeInt(headers.getInt(MessageHeader.getHeader(i)));
            }
            for (int i = 4; i < 8; i++) {
                if ((key >> i & 1) == 1)
                    out.writeLong(headers.getLong(MessageHeader.getHeader(i)));
            }
            for (int i = 8; i < 10; i++) {
                if ((key >> i & 1) == 1)
                    out.writeDouble(headers.getDouble(MessageHeader.getHeader(i)));
            }
            for (int i = 11; i < 15; i++) {
                if ((key >> i & 1) == 1) {
                    String strVal = headers.getString(MessageHeader.getHeader(i));
                    out.writeByte(strVal.getBytes().length);
                    out.write(strVal.getBytes());
                }
            }
/*

            // write headers —— size, key index, valueLength, valueBytes
            out.writeByte(msg.headers().getMap().size());
            String headerKey;
            for (Map.Entry<String, Object> entry : msg.headers().getMap().entrySet()) {
                headerKey = entry.getKey();
                int index = MessageHeader.getHeaderIndex(headerKey);
                if (index == 15) continue;  // 不需要写入TOPIC，读取时根据文件名加入
                out.writeByte(index);

                // 0-3, 4-7, 8-9, 10-15
                if (index <= 3) {
                    //out.writeByte(4); // 知道int数据类型长度，不需要存
                    out.writeInt((int) entry.getValue());
                } else if (index <= 7) {
                    //out.writeByte(8);
                    out.writeLong((long) entry.getValue());
                } else if (index <= 9) {
                    //out.writeByte(8);
                    out.writeDouble((double) entry.getValue());
                } else {
                    String strVal = (String) entry.getValue();
                    out.writeByte(strVal.getBytes().length);
                    out.write(strVal.getBytes());
                }
            }

*/

            // write body's length, byte[]
            int bodyLen = msg.getBody().length;
            if (bodyLen <= Byte.MAX_VALUE) {
                out.writeByte(0);
                out.writeByte(bodyLen);
            } else if (bodyLen <= Short.MAX_VALUE){
                out.writeByte(1);  // body[] 的长度 > 127，即超过byte，先存入 1 ，再存入用int表示的长度
                out.writeShort(bodyLen);
            } else {
                out.writeByte(2);
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

            String inKey = queue + topic;
            in = inMap.get(inKey);
            if (in == null) {   // 不含则新建 buffer
                File file = new File(FILE_DIR + topic );
                if (!file.exists()) {       //判断topic文件是否存在，不存在的话返回null，否则建立内存映射
                    return null;
                }

                FileChannel fc = new RandomAccessFile(file, "r").getChannel();
                in = fc.map(FileChannel.MapMode.READ_ONLY, 0, fc.size());

                inMap.put(inKey, in);
            }

            // 这个流已经读完
            if (!in.hasRemaining()) {
                inMap.remove(inKey);
                return null;
            }



            // 读取 headers 部分
            KeyValue headers = new DefaultKeyValue();
            headers.put(MessageHeader.TOPIC, topic);    // 直接写入 topic
            short key = in.getShort();
            for (int i = 0; i < 4; i++) {
                if ((key >> i & 1) == 1)
                    headers.put(MessageHeader.getHeader(i), in.getInt());
            }
            for (int i = 4; i < 8; i++) {
                if ((key >> i & 1) == 1)
                    headers.put(MessageHeader.getHeader(i), in.getLong());
            }
            for (int i = 8; i < 10; i++) {
                if ((key >> i & 1) == 1)
                    headers.put(MessageHeader.getHeader(i), in.getDouble());
            }
            for (int i = 11; i < 15; i++) {
                if ((key >> i & 1) == 1) {
                    byte vLen = in.get();    // valueLength
                    byte[] vals = new byte[vLen];    // value
                    in.get(vals);
                    headers.put(MessageHeader.getHeader(i), new String(vals));
                }
            }


            /*

            // 读取 headers 部分
            KeyValue headers = new DefaultKeyValue();
            headers.put(MessageHeader.TOPIC, topic);    // 直接写入 topic
            int headerSize = in.get();
            for (int i = 1; i < headerSize; i++) {  // 少了一轮 topic
                int index = in.get();

                // 0-3 int, 4-7 long, 8-9 double, 10-15 string
                if (index <= 3) {
                    headers.put(MessageHeader.getHeader(index), in.getInt());
                } else if (index <= 7) {
                    headers.put(MessageHeader.getHeader(index), in.getLong());
                } else if (index <= 9) {
                    headers.put(MessageHeader.getHeader(index), in.getDouble());
                } else {
                    byte vLen = in.get();    // valueLength
                    byte[] vals = new byte[vLen];    // value
                    in.get(vals);
                    headers.put(MessageHeader.getHeader(index), new String(vals));
                }
            }*/


            // 读取 body 部分
            byte type = in.get();
            byte[] body;
            if (type == 0) {
                body = new byte[in.get()];
            } else if (type == 1) {
                body = new byte[in.getShort()];
            } else {
                body = new byte[in.getInt()];
            }
            in.get(body);


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
