package pku;

import java.util.HashSet;
import java.util.Set;

/**
 * 生产者: 依次遍历 topics 每个 topic 生产 PUSH_COUNT 个消息
 */
public class Producer {

    private Set<String> topics = new HashSet<>();

	// 生成一个指定topic的message返回
    public ByteMessage createBytesMessageToTopic(String topic, byte[] body) {
        topics.add(topic);

        ByteMessage msg = new DefaultMessage(body);
        msg.putHeaders(MessageHeader.TOPIC, topic);
        return msg;
    }

    //将message发送出去
    public void send(ByteMessage msg) {
        String topic = msg.headers().getString(MessageHeader.TOPIC);
        DemoMessageStore.store.bufferPush(msg, topic);
    }

    //处理将缓存区的剩余部分
    public void flush()throws Exception {
        DemoMessageStore.store.bFlush(topics);
        System.out.println("flush");
    }
}
