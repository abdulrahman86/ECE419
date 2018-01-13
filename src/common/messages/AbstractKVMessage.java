package common.messages;

/**
 * Base class of KV Message.
 * <p>
 * Provides the common fields used by most KVMessage implementations.
 * <p>
 * Created by Charlie on 2018-01-12.
 */
public abstract class AbstractKVMessage implements KVMessage {
    String key;
    String value;
    StatusType status;

    @Override
    public String getKey() {
        return key;
    }

    @Override
    public String getValue() {
        return value;
    }

    @Override
    public StatusType getStatus() {
        return status;
    }

    public AbstractKVMessage() {}

    public AbstractKVMessage(String key, String value, StatusType status) {
        this();
        this.key = key;
        this.value = value;
        this.status = status;
    }

    public AbstractKVMessage(String key, String value, String status) {
        this(key, value, StatusType.valueOf(status));
    }

    public AbstractKVMessage(String data) {
        this();
        decode(data);
    }
}