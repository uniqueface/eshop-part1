package com.ruyuan.eshop.common.constants;

/**
 * RocketMQ 常量类
 *
 * @author zhonghuashishan
 * @version 1.0
 */
public class RocketMqConstant {

    /**
     * 默认的producer分组
     */
    public static String ORDER_DEFAULT_PRODUCER_GROUP = "order_default_producer_group";

    /**
     * 支付订单超时自动关单发送延迟消息 topic
     */
    public static String PAY_ORDER_TIMEOUT_DELAY_TOPIC = "pay_order_timeout_delay_topic";

    /**
     * 支付订单超时自动关单 consumer 分组
     */
    public static String PAY_ORDER_TIMEOUT_DELAY_CONSUMER_GROUP = "pay_order_timeout_delay_consumer_group";

    /**
     * 完成订单支付发送普通消息 topic
     */
    public static String PAID_ORDER_SUCCESS_TOPIC = "paid_order_success_topic";

    /**
     * 完成订单支付 consumer 分组
     */
    public static String PAID_ORDER_SUCCESS_CONSUMER_GROUP = "paid_order_success_consumer_group";

    /**
     * 取消订单 发送释放权益 topic
     */
    public static String RELEASE_ASSETS_TOPIC = "release_assets_topic";

    /**
     * 取消订单 发送释放库存 topic
     */
    public static String CANCEL_RELEASE_INVENTORY_TOPIC = "release_inventory_topic";

    /**
     * 取消订单 发送释放权益资产 topic
     */
    public static String CANCEL_RELEASE_PROPERTY_TOPIC = "release_property_topic";

    /**
     * 取消订单 发送退款请求 topic
     */
    public static String CANCEL_REFUND_REQUEST_TOPIC = "refund_request_topic";

    /**
     * 发送实际退款 topic
     */
    public static String ACTUAL_REFUND_TOPIC = "actual_refund_topic";

    /**
     * 监听实际退款分组
     */
    public static String ACTUAL_REFUND_CONSUMER_GROUP = "actual_refund_consumer_group";

    /**
     * 监听退款请求分组
     */
    public static String REQUEST_CONSUMER_GROUP = "request_consumer_group";

    /**
     * 监听释放权益资产分组
     */
    public static String RELEASE_PROPERTY_CONSUMER_GROUP = "release_property_consumer_group";

    /**
     * 监听释放库存分组
     */
    public static String RELEASE_INVENTORY_CONSUMER_GROUP = "release_inventory_consumer_group";

    /**
     * 监听释放资产分组
     */
    public static String RELEASE_ASSETS_CONSUMER_GROUP = "release_assets_consumer_group";

    /**
     * 正向订单物流配送结果结果相关的topic信息
     */
    public static String ORDER_WMS_SHIP_RESULT_TOPIC = "wms_ship_result_topic";

    /**
     * 正向订单物流配送结果结果 consumer 分组
     */
    public static String ORDER_WMS_SHIP_RESULT_CONSUMER_GROUP = "wms_ship_result_consumer_group";

}
