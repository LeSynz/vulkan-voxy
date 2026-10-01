package me.cortex.voxy.client.core.vulkan;

public class VkOutOfMemoryException extends RuntimeException {
    public final int result;
    public VkOutOfMemoryException(String message, int result) {
        super(message);
        this.result = result;
    }
}
