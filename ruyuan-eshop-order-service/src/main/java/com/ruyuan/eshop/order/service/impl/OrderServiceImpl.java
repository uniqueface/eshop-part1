package com.ruyuan.eshop.order.service.impl;

import com.alibaba.fastjson.JSON;
import com.ruyuan.eshop.address.api.AddressApi;
import com.ruyuan.eshop.address.domain.dto.AddressDTO;
import com.ruyuan.eshop.address.domain.query.AddressQuery;
import com.ruyuan.eshop.common.constants.RedisLockKeyConstants;
import com.ruyuan.eshop.common.constants.RocketDelayedLevel;
import com.ruyuan.eshop.common.constants.RocketMqConstant;
import com.ruyuan.eshop.common.core.CloneDirection;
import com.ruyuan.eshop.common.core.JsonResult;
import com.ruyuan.eshop.common.enums.*;
import com.ruyuan.eshop.common.message.PaidOrderSuccessMessage;
import com.ruyuan.eshop.common.message.PayOrderTimeoutDelayMessage;
import com.ruyuan.eshop.common.redis.RedisLock;
import com.ruyuan.eshop.common.utils.JsonUtil;
import com.ruyuan.eshop.common.utils.ObjectUtil;
import com.ruyuan.eshop.common.utils.ParamCheckUtil;
import com.ruyuan.eshop.inventory.api.InventoryApi;
import com.ruyuan.eshop.inventory.domain.request.LockProductStockRequest;
import com.ruyuan.eshop.market.api.MarketApi;
import com.ruyuan.eshop.market.domain.dto.CalculateOrderAmountDTO;
import com.ruyuan.eshop.market.domain.dto.UserCouponDTO;
import com.ruyuan.eshop.market.domain.query.UserCouponQuery;
import com.ruyuan.eshop.market.domain.request.CalculateOrderAmountRequest;
import com.ruyuan.eshop.market.domain.request.LockUserCouponRequest;
import com.ruyuan.eshop.order.builder.NewOrderBuilder;
import com.ruyuan.eshop.order.builder.FullOrderData;
import com.ruyuan.eshop.order.config.OrderProperties;
import com.ruyuan.eshop.order.dao.*;
import com.ruyuan.eshop.order.domain.dto.*;
import com.ruyuan.eshop.order.domain.entity.*;
import com.ruyuan.eshop.order.domain.request.*;
import com.ruyuan.eshop.order.enums.*;
import com.ruyuan.eshop.order.exception.OrderBizException;
import com.ruyuan.eshop.order.exception.OrderErrorCodeEnum;
import com.ruyuan.eshop.order.manager.OrderNoManager;
import com.ruyuan.eshop.order.mq.producer.DefaultProducer;
import com.ruyuan.eshop.order.service.OrderService;
import com.ruyuan.eshop.pay.api.PayApi;
import com.ruyuan.eshop.pay.domain.dto.PayOrderDTO;
import com.ruyuan.eshop.pay.domain.request.PayOrderRequest;
import com.ruyuan.eshop.pay.domain.request.PayRefundRequest;
import com.ruyuan.eshop.product.api.ProductApi;
import com.ruyuan.eshop.product.domain.dto.ProductSkuDTO;
import com.ruyuan.eshop.product.domain.query.ProductSkuQuery;
import com.ruyuan.eshop.risk.api.RiskApi;
import com.ruyuan.eshop.risk.domain.dto.CheckOrderRiskDTO;
import com.ruyuan.eshop.risk.domain.request.CheckOrderRiskRequest;
import io.seata.spring.annotation.GlobalTransactional;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * @author zhonghuashishan
 * @version 1.0
 */
@Slf4j
@Service
public class OrderServiceImpl implements OrderService {

    @Autowired
    private OrderInfoDAO orderInfoDAO;

    @Autowired
    private OrderItemDAO orderItemDAO;

    @Autowired
    private OrderDeliveryDetailDAO orderDeliveryDetailDAO;

    @Autowired
    private OrderPaymentDetailDAO orderPaymentDetailDAO;

    @Autowired
    private OrderAmountDAO orderAmountDAO;

    @Autowired
    private OrderAmountDetailDAO orderAmountDetailDAO;

    @Autowired
    private OrderOperateLogDAO orderOperateLogDAO;

    @Autowired
    private OrderSnapshotDAO orderSnapshotDAO;

    @Autowired
    private OrderNoManager orderNoManager;

    @Autowired
    private DefaultProducer defaultProducer;

    @Autowired
    private OrderProperties orderProperties;

    @Autowired
    private RedisLock redisLock;

    /**
     * ????????????
     */
    @DubboReference(version = "1.0.0")
    private ProductApi productApi;

    /**
     * ????????????
     */
    @DubboReference(version = "1.0.0", retries = 0)
    private InventoryApi inventoryApi;

    /**
     * ????????????
     */
    @DubboReference(version = "1.0.0", retries = 0)
    private MarketApi marketApi;

    /**
     * ????????????
     */
    @DubboReference(version = "1.0.0", retries = 0)
    private RiskApi riskApi;

    /**
     * ????????????
     */
    @DubboReference(version = "1.0.0", retries = 0)
    private PayApi payApi;

    /**
     * ????????????
     */
    @DubboReference(version = "1.0.0")
    private AddressApi addressApi;

    /**
     * ?????????????????????
     *
     * @param genOrderIdRequest ?????????????????????
     * @return ?????????
     */
    @Override
    public GenOrderIdDTO genOrderId(GenOrderIdRequest genOrderIdRequest) {
        // ????????????
        String userId = genOrderIdRequest.getUserId();
        ParamCheckUtil.checkStringNonEmpty(userId);
        Integer businessIdentifier = genOrderIdRequest.getBusinessIdentifier();
        ParamCheckUtil.checkObjectNonNull(businessIdentifier);

        String orderId = orderNoManager.genOrderId(OrderNoTypeEnum.SALE_ORDER.getCode(), userId);
        GenOrderIdDTO genOrderIdDTO = new GenOrderIdDTO();
        genOrderIdDTO.setOrderId(orderId);
        return genOrderIdDTO;
    }

    /**
     * ????????????/??????????????????
     *
     * @param createOrderRequest ????????????????????????
     * @return ?????????
     */
    @GlobalTransactional(rollbackFor = Exception.class)
    @Override
    public CreateOrderDTO createOrder(CreateOrderRequest createOrderRequest) {
        // 1???????????????
        checkCreateOrderRequestParam(createOrderRequest);

        // 2???????????????
        checkRisk(createOrderRequest);

        // 3?????????????????????
        List<ProductSkuDTO> productSkuList = listProductSkus(createOrderRequest);

        // 4?????????????????????
        CalculateOrderAmountDTO calculateOrderAmountDTO = calculateOrderAmount(createOrderRequest, productSkuList);

        // 5???????????????????????????
        checkRealPayAmount(createOrderRequest, calculateOrderAmountDTO);

        // 6??????????????????
        lockUserCoupon(createOrderRequest);

        // 7?????????????????????
        lockProductStock(createOrderRequest);

        // 8???????????????????????????
        addNewOrder(createOrderRequest, productSkuList, calculateOrderAmountDTO);

        // 9?????????????????????????????????????????????????????????
        sendPayOrderTimeoutDelayMessage(createOrderRequest);

        // ??????????????????
        CreateOrderDTO createOrderDTO = new CreateOrderDTO();
        createOrderDTO.setOrderId(createOrderRequest.getOrderId());
        return createOrderDTO;
    }

    /**
     * ??????????????????????????????
     */
    private void checkCreateOrderRequestParam(CreateOrderRequest createOrderRequest) {
        ParamCheckUtil.checkObjectNonNull(createOrderRequest);

        // ??????ID
        String orderId = createOrderRequest.getOrderId();
        ParamCheckUtil.checkStringNonEmpty(orderId, OrderErrorCodeEnum.ORDER_ID_IS_NULL);

        // ???????????????
        Integer businessIdentifier = createOrderRequest.getBusinessIdentifier();
        ParamCheckUtil.checkObjectNonNull(businessIdentifier, OrderErrorCodeEnum.BUSINESS_IDENTIFIER_IS_NULL);
        if (BusinessIdentifierEnum.getByCode(businessIdentifier) == null) {
            throw new OrderBizException(OrderErrorCodeEnum.BUSINESS_IDENTIFIER_ERROR);
        }

        // ??????ID
        String userId = createOrderRequest.getUserId();
        ParamCheckUtil.checkStringNonEmpty(userId, OrderErrorCodeEnum.USER_ID_IS_NULL);

        // ????????????
        Integer orderType = createOrderRequest.getOrderType();
        ParamCheckUtil.checkObjectNonNull(businessIdentifier, OrderErrorCodeEnum.ORDER_TYPE_IS_NULL);
        if (OrderTypeEnum.getByCode(orderType) == null) {
            throw new OrderBizException(OrderErrorCodeEnum.ORDER_TYPE_ERROR);
        }

        // ??????ID
        String sellerId = createOrderRequest.getSellerId();
        ParamCheckUtil.checkStringNonEmpty(sellerId, OrderErrorCodeEnum.SELLER_ID_IS_NULL);

        // ????????????
        Integer deliveryType = createOrderRequest.getDeliveryType();
        ParamCheckUtil.checkObjectNonNull(deliveryType, OrderErrorCodeEnum.USER_ADDRESS_ERROR);
        if (DeliveryTypeEnum.getByCode(deliveryType) == null) {
            throw new OrderBizException(OrderErrorCodeEnum.DELIVERY_TYPE_ERROR);
        }

        // ????????????
        String province = createOrderRequest.getProvince();
        String city = createOrderRequest.getCity();
        String area = createOrderRequest.getArea();
        String streetAddress = createOrderRequest.getStreet();
        ParamCheckUtil.checkStringNonEmpty(province, OrderErrorCodeEnum.USER_ADDRESS_ERROR);
        ParamCheckUtil.checkStringNonEmpty(city, OrderErrorCodeEnum.USER_ADDRESS_ERROR);
        ParamCheckUtil.checkStringNonEmpty(area, OrderErrorCodeEnum.USER_ADDRESS_ERROR);
        ParamCheckUtil.checkStringNonEmpty(streetAddress, OrderErrorCodeEnum.USER_ADDRESS_ERROR);

        // ??????ID
        String regionId = createOrderRequest.getRegionId();
        ParamCheckUtil.checkStringNonEmpty(regionId, OrderErrorCodeEnum.REGION_ID_IS_NULL);

        // ?????????
        BigDecimal lon = createOrderRequest.getLon();
        BigDecimal lat = createOrderRequest.getLat();
        ParamCheckUtil.checkObjectNonNull(lon, OrderErrorCodeEnum.USER_LOCATION_IS_NULL);
        ParamCheckUtil.checkObjectNonNull(lat, OrderErrorCodeEnum.USER_LOCATION_IS_NULL);

        // ???????????????
        String receiverName = createOrderRequest.getReceiverName();
        String receiverPhone = createOrderRequest.getReceiverPhone();
        ParamCheckUtil.checkStringNonEmpty(receiverName, OrderErrorCodeEnum.ORDER_RECEIVER_IS_NULL);
        ParamCheckUtil.checkStringNonEmpty(receiverPhone, OrderErrorCodeEnum.ORDER_RECEIVER_IS_NULL);

        // ?????????????????????
        String clientIp = createOrderRequest.getClientIp();
        ParamCheckUtil.checkStringNonEmpty(clientIp, OrderErrorCodeEnum.CLIENT_IP_IS_NULL);

        // ??????????????????
        List<CreateOrderRequest.OrderItemRequest> orderItemRequestList = createOrderRequest.getOrderItemRequestList();
        ParamCheckUtil.checkCollectionNonEmpty(orderItemRequestList, OrderErrorCodeEnum.ORDER_ITEM_IS_NULL);

        for (CreateOrderRequest.OrderItemRequest orderItemRequest : orderItemRequestList) {
            Integer productType = orderItemRequest.getProductType();
            Integer saleQuantity = orderItemRequest.getSaleQuantity();
            String skuCode = orderItemRequest.getSkuCode();
            ParamCheckUtil.checkObjectNonNull(productType, OrderErrorCodeEnum.ORDER_ITEM_PARAM_ERROR);
            ParamCheckUtil.checkObjectNonNull(saleQuantity, OrderErrorCodeEnum.ORDER_ITEM_PARAM_ERROR);
            ParamCheckUtil.checkStringNonEmpty(skuCode, OrderErrorCodeEnum.ORDER_ITEM_PARAM_ERROR);
        }

        // ??????????????????
        List<CreateOrderRequest.OrderAmountRequest> orderAmountRequestList = createOrderRequest.getOrderAmountRequestList();
        ParamCheckUtil.checkCollectionNonEmpty(orderAmountRequestList, OrderErrorCodeEnum.ORDER_AMOUNT_IS_NULL);

        for (CreateOrderRequest.OrderAmountRequest orderAmountRequest : orderAmountRequestList) {
            Integer amountType = orderAmountRequest.getAmountType();
            ParamCheckUtil.checkObjectNonNull(amountType, OrderErrorCodeEnum.ORDER_AMOUNT_TYPE_IS_NULL);

            if (AmountTypeEnum.getByCode(amountType) == null) {
                throw new OrderBizException(OrderErrorCodeEnum.ORDER_AMOUNT_TYPE_PARAM_ERROR);
            }
        }
        Map<Integer, Integer> orderAmountMap = orderAmountRequestList.stream()
                .collect(Collectors.toMap(CreateOrderRequest.OrderAmountRequest::getAmountType,
                        CreateOrderRequest.OrderAmountRequest::getAmount));

        // ??????????????????????????????
        if (orderAmountMap.get(AmountTypeEnum.ORIGIN_PAY_AMOUNT.getCode()) == null) {
            throw new OrderBizException(OrderErrorCodeEnum.ORDER_ORIGIN_PAY_AMOUNT_IS_NULL);
        }
        // ????????????????????????
        if (orderAmountMap.get(AmountTypeEnum.SHIPPING_AMOUNT.getCode()) == null) {
            throw new OrderBizException(OrderErrorCodeEnum.ORDER_SHIPPING_AMOUNT_IS_NULL);
        }
        // ??????????????????????????????
        if (orderAmountMap.get(AmountTypeEnum.REAL_PAY_AMOUNT.getCode()) == null) {
            throw new OrderBizException(OrderErrorCodeEnum.ORDER_REAL_PAY_AMOUNT_IS_NULL);
        }
        if (StringUtils.isNotEmpty(createOrderRequest.getCouponId())) {
            // ???????????????????????????????????????
            if (orderAmountMap.get(AmountTypeEnum.COUPON_DISCOUNT_AMOUNT.getCode()) == null) {
                throw new OrderBizException(OrderErrorCodeEnum.ORDER_DISCOUNT_AMOUNT_IS_NULL);
            }
        }

        // ??????????????????
        List<CreateOrderRequest.PaymentRequest> paymentRequestList = createOrderRequest.getPaymentRequestList();
        ParamCheckUtil.checkCollectionNonEmpty(paymentRequestList, OrderErrorCodeEnum.ORDER_PAYMENT_IS_NULL);

        for (CreateOrderRequest.PaymentRequest paymentRequest : paymentRequestList) {
            Integer payType = paymentRequest.getPayType();
            Integer accountType = paymentRequest.getAccountType();
            if (payType == null || PayTypeEnum.getByCode(payType) == null) {
                throw new OrderBizException(OrderErrorCodeEnum.PAY_TYPE_PARAM_ERROR);
            }
            if (accountType == null || AccountTypeEnum.getByCode(accountType) == null) {
                throw new OrderBizException(OrderErrorCodeEnum.ACCOUNT_TYPE_PARAM_ERROR);
            }
        }

    }

    /**
     * ????????????
     */
    private void checkRisk(CreateOrderRequest createOrderRequest) {
        // ????????????????????????????????????
        CheckOrderRiskRequest checkOrderRiskRequest = createOrderRequest.clone(CheckOrderRiskRequest.class);
        JsonResult<CheckOrderRiskDTO> jsonResult = riskApi.checkOrderRisk(checkOrderRiskRequest);
        if (!jsonResult.getSuccess()) {
            throw new OrderBizException(jsonResult.getErrorCode(), jsonResult.getErrorMessage());
        }
    }

    /**
     * ?????????????????????
     */
    private void lockUserCoupon(CreateOrderRequest createOrderRequest) {
        String couponId = createOrderRequest.getCouponId();
        if (StringUtils.isEmpty(couponId)) {
            return;
        }
        LockUserCouponRequest lockUserCouponRequest = createOrderRequest.clone(LockUserCouponRequest.class);
        // ???????????????????????????????????????
        JsonResult<Boolean> jsonResult = marketApi.lockUserCoupon(lockUserCouponRequest);
        // ?????????????????????????????????
        if (!jsonResult.getSuccess()) {
            throw new OrderBizException(jsonResult.getErrorCode(), jsonResult.getErrorMessage());
        }
    }

    /**
     * ??????????????????
     *
     * @param createOrderRequest ????????????
     */
    private void lockProductStock(CreateOrderRequest createOrderRequest) {
        String orderId = createOrderRequest.getOrderId();
        List<LockProductStockRequest.OrderItemRequest> orderItemRequestList = ObjectUtil.convertList(
                createOrderRequest.getOrderItemRequestList(), LockProductStockRequest.OrderItemRequest.class);

        LockProductStockRequest lockProductStockRequest = new LockProductStockRequest();
        lockProductStockRequest.setOrderId(orderId);
        lockProductStockRequest.setOrderItemRequestList(orderItemRequestList);
        JsonResult<Boolean> jsonResult = inventoryApi.lockProductStock(lockProductStockRequest);
        // ??????????????????????????????
        if (!jsonResult.getSuccess()) {
            throw new OrderBizException(jsonResult.getErrorCode(), jsonResult.getErrorMessage());
        }
    }

    /**
     * ??????????????????????????????
     */
    private List<ProductSkuDTO> listProductSkus(CreateOrderRequest createOrderRequest) {
        List<CreateOrderRequest.OrderItemRequest> orderItemRequestList = createOrderRequest.getOrderItemRequestList();
        List<ProductSkuDTO> productSkuList = new ArrayList<>();
        for (CreateOrderRequest.OrderItemRequest orderItemRequest : orderItemRequestList) {
            String skuCode = orderItemRequest.getSkuCode();

            ProductSkuQuery productSkuQuery = new ProductSkuQuery();
            productSkuQuery.setSkuCode(skuCode);
            productSkuQuery.setSellerId(createOrderRequest.getSellerId());
            JsonResult<ProductSkuDTO> jsonResult = productApi.getProductSku(productSkuQuery);
            if (!jsonResult.getSuccess()) {
                throw new OrderBizException(jsonResult.getErrorCode(), jsonResult.getErrorMessage());
            }
            ProductSkuDTO productSkuDTO = jsonResult.getData();
            // sku?????????
            if (productSkuDTO == null) {
                throw new OrderBizException(OrderErrorCodeEnum.PRODUCT_SKU_CODE_ERROR, skuCode);
            }
            productSkuList.add(productSkuDTO);
        }
        return productSkuList;
    }

    /**
     * ??????????????????
     * ?????????????????????????????????????????????????????????????????????
     *
     * @param createOrderRequest ????????????
     * @param productSkuList     ????????????
     */
    private CalculateOrderAmountDTO calculateOrderAmount(CreateOrderRequest createOrderRequest, List<ProductSkuDTO> productSkuList) {
        CalculateOrderAmountRequest calculateOrderPriceRequest = createOrderRequest.clone(CalculateOrderAmountRequest.class, CloneDirection.FORWARD);

        // ??????????????????????????????
        Map<String, ProductSkuDTO> productSkuDTOMap = productSkuList.stream().collect(Collectors.toMap(ProductSkuDTO::getSkuCode, Function.identity()));
        calculateOrderPriceRequest.getOrderItemRequestList().forEach(item -> {
            String skuCode = item.getSkuCode();
            ProductSkuDTO productSkuDTO = productSkuDTOMap.get(skuCode);
            item.setProductId(productSkuDTO.getProductId());
            item.setSalePrice(productSkuDTO.getSalePrice());
        });

        // ????????????????????????????????????
        JsonResult<CalculateOrderAmountDTO> jsonResult = marketApi.calculateOrderAmount(calculateOrderPriceRequest);

        // ????????????????????????
        if (!jsonResult.getSuccess()) {
            throw new OrderBizException(jsonResult.getErrorCode(), jsonResult.getErrorMessage());
        }
        CalculateOrderAmountDTO calculateOrderAmountDTO = jsonResult.getData();
        if (calculateOrderAmountDTO == null) {
            throw new OrderBizException(OrderErrorCodeEnum.CALCULATE_ORDER_AMOUNT_ERROR);
        }
        // ??????????????????
        List<OrderAmountDTO> orderAmountList = ObjectUtil.convertList(calculateOrderAmountDTO.getOrderAmountList(), OrderAmountDTO.class);
        if (orderAmountList == null || orderAmountList.isEmpty()) {
            throw new OrderBizException(OrderErrorCodeEnum.CALCULATE_ORDER_AMOUNT_ERROR);
        }

        // ????????????????????????
        List<OrderAmountDetailDTO> orderItemAmountList = ObjectUtil.convertList(calculateOrderAmountDTO.getOrderAmountDetail(), OrderAmountDetailDTO.class);
        if (orderItemAmountList == null || orderItemAmountList.isEmpty()) {
            throw new OrderBizException(OrderErrorCodeEnum.CALCULATE_ORDER_AMOUNT_ERROR);
        }
        return calculateOrderAmountDTO;
    }

    /**
     * ????????????????????????
     */
    private void checkRealPayAmount(CreateOrderRequest createOrderRequest, CalculateOrderAmountDTO calculateOrderAmountDTO) {
        List<CreateOrderRequest.OrderAmountRequest> originOrderAmountRequestList = createOrderRequest.getOrderAmountRequestList();
        Map<Integer, CreateOrderRequest.OrderAmountRequest> originOrderAmountMap =
                originOrderAmountRequestList.stream().collect(Collectors.toMap(
                        CreateOrderRequest.OrderAmountRequest::getAmountType, Function.identity()));
        // ????????????????????????
        Integer originRealPayAmount = originOrderAmountMap.get(AmountTypeEnum.REAL_PAY_AMOUNT.getCode()).getAmount();


        List<CalculateOrderAmountDTO.OrderAmountDTO> orderAmountDTOList = calculateOrderAmountDTO.getOrderAmountList();
        Map<Integer, CalculateOrderAmountDTO.OrderAmountDTO> orderAmountMap =
                orderAmountDTOList.stream().collect(Collectors.toMap(CalculateOrderAmountDTO.OrderAmountDTO::getAmountType, Function.identity()));
        // ?????????????????????????????????
        Integer realPayAmount = orderAmountMap.get(AmountTypeEnum.REAL_PAY_AMOUNT.getCode()).getAmount();

        if (!originRealPayAmount.equals(realPayAmount)) {
            // ??????????????????
            throw new OrderBizException(OrderErrorCodeEnum.ORDER_CHECK_REAL_PAY_AMOUNT_FAIL);
        }
    }

    /**
     * ??????????????????????????????
     */
    private void addNewOrder(CreateOrderRequest createOrderRequest, List<ProductSkuDTO> productSkuList, CalculateOrderAmountDTO calculateOrderAmountDTO) {

        // ?????????????????????
        NewOrderDataHolder newOrderDataHolder = new NewOrderDataHolder();

        // ???????????????
        FullOrderData fullMasterOrderData = addNewMasterOrder(createOrderRequest, productSkuList, calculateOrderAmountDTO);

        // ????????????????????????NewOrderData?????????
        newOrderDataHolder.appendOrderData(fullMasterOrderData);


        // ??????????????????????????????????????????????????????????????????
        Map<Integer, List<ProductSkuDTO>> productTypeMap = productSkuList.stream().collect(Collectors.groupingBy(ProductSkuDTO::getProductType));
        if (productTypeMap.keySet().size() > 1) {
            for (Integer productType : productTypeMap.keySet()) {
                // ???????????????
                FullOrderData fullSubOrderData = addNewSubOrder(fullMasterOrderData, productType);

                // ????????????????????????NewOrderData?????????
                newOrderDataHolder.appendOrderData(fullSubOrderData);
            }
        }

        // ????????????????????????
        // ????????????
        List<OrderInfoDO> orderInfoDOList = newOrderDataHolder.getOrderInfoDOList();
        if (!orderInfoDOList.isEmpty()) {
            orderInfoDAO.saveBatch(orderInfoDOList);
        }

        // ????????????
        List<OrderItemDO> orderItemDOList = newOrderDataHolder.getOrderItemDOList();
        if (!orderItemDOList.isEmpty()) {
            orderItemDAO.saveBatch(orderItemDOList);
        }

        // ??????????????????
        List<OrderDeliveryDetailDO> orderDeliveryDetailDOList = newOrderDataHolder.getOrderDeliveryDetailDOList();
        if (!orderDeliveryDetailDOList.isEmpty()) {
            orderDeliveryDetailDAO.saveBatch(orderDeliveryDetailDOList);
        }

        // ??????????????????
        List<OrderPaymentDetailDO> orderPaymentDetailDOList = newOrderDataHolder.getOrderPaymentDetailDOList();
        if (!orderPaymentDetailDOList.isEmpty()) {
            orderPaymentDetailDAO.saveBatch(orderPaymentDetailDOList);
        }

        // ??????????????????
        List<OrderAmountDO> orderAmountDOList = newOrderDataHolder.getOrderAmountDOList();
        if (!orderAmountDOList.isEmpty()) {
            orderAmountDAO.saveBatch(orderAmountDOList);
        }

        // ??????????????????
        List<OrderAmountDetailDO> orderAmountDetailDOList = newOrderDataHolder.getOrderAmountDetailDOList();
        if (!orderAmountDetailDOList.isEmpty()) {
            orderAmountDetailDAO.saveBatch(orderAmountDetailDOList);
        }

        // ??????????????????????????????
        List<OrderOperateLogDO> orderOperateLogDOList = newOrderDataHolder.getOrderOperateLogDOList();
        if (!orderOperateLogDOList.isEmpty()) {
            orderOperateLogDAO.saveBatch(orderOperateLogDOList);
        }

        // ??????????????????
        List<OrderSnapshotDO> orderSnapshotDOList = newOrderDataHolder.getOrderSnapshotDOList();
        if (!orderSnapshotDOList.isEmpty()) {
            orderSnapshotDAO.saveBatch(orderSnapshotDOList);
        }
    }

    /**
     * ???????????????????????????
     */
    private FullOrderData addNewMasterOrder(CreateOrderRequest createOrderRequest, List<ProductSkuDTO> productSkuList, CalculateOrderAmountDTO calculateOrderAmountDTO) {

        NewOrderBuilder newOrderBuilder = new NewOrderBuilder(createOrderRequest, productSkuList, calculateOrderAmountDTO, orderProperties);
        FullOrderData fullOrderData = newOrderBuilder.buildOrder()
                .buildOrderItems()
                .buildOrderDeliveryDetail()
                .buildOrderPaymentDetail()
                .buildOrderAmount()
                .buildOrderAmountDetail()
                .buildOperateLog()
                .buildOrderSnapshot()
                .build();

        // ????????????
        OrderInfoDO orderInfoDO = fullOrderData.getOrderInfoDO();

        // ??????????????????
        List<OrderItemDO> orderItemDOList = fullOrderData.getOrderItemDOList();

        // ??????????????????
        List<OrderAmountDO> orderAmountDOList = fullOrderData.getOrderAmountDOList();

        // ??????????????????
        OrderDeliveryDetailDO orderDeliveryDetailDO = fullOrderData.getOrderDeliveryDetailDO();
        String detailAddress = getDetailAddress(orderDeliveryDetailDO);
        orderDeliveryDetailDO.setDetailAddress(detailAddress);

        // ??????????????????????????????
        OrderOperateLogDO orderOperateLogDO = fullOrderData.getOrderOperateLogDO();
        String remark = "??????????????????0-10";
        orderOperateLogDO.setRemark(remark);

        // ??????????????????????????????
        List<OrderSnapshotDO> orderSnapshotDOList = fullOrderData.getOrderSnapshotDOList();
        for (OrderSnapshotDO orderSnapshotDO : orderSnapshotDOList) {
            // ???????????????
            if (orderSnapshotDO.getSnapshotType().equals(SnapshotTypeEnum.ORDER_COUPON.getCode())) {
                String couponId = orderInfoDO.getCouponId();
                String userId = orderInfoDO.getUserId();
                UserCouponQuery userCouponQuery = new UserCouponQuery();
                userCouponQuery.setCouponId(couponId);
                userCouponQuery.setUserId(userId);
                JsonResult<UserCouponDTO> jsonResult = marketApi.getUserCoupon(userCouponQuery);
                if (jsonResult.getSuccess()) {
                    UserCouponDTO userCouponDTO = jsonResult.getData();
                    if (userCouponDTO != null) {
                        orderSnapshotDO.setSnapshotJson(JsonUtil.object2Json(userCouponDTO));
                    }
                } else {
                    orderSnapshotDO.setSnapshotJson(JsonUtil.object2Json(couponId));
                }
            }
            // ??????????????????
            else if (orderSnapshotDO.getSnapshotType().equals(SnapshotTypeEnum.ORDER_AMOUNT.getCode())) {
                orderSnapshotDO.setSnapshotJson(JsonUtil.object2Json(orderAmountDOList));
            }
            // ??????????????????
            else if (orderSnapshotDO.getSnapshotType().equals(SnapshotTypeEnum.ORDER_ITEM.getCode())) {
                orderSnapshotDO.setSnapshotJson(JsonUtil.object2Json(orderItemDOList));
            }
        }

        return fullOrderData;
    }

    /**
     * ??????????????????????????????
     */
    private String getDetailAddress(OrderDeliveryDetailDO orderDeliveryDetailDO) {
        String provinceCode = orderDeliveryDetailDO.getProvince();
        String cityCode = orderDeliveryDetailDO.getCity();
        String areaCode = orderDeliveryDetailDO.getArea();
        String streetCode = orderDeliveryDetailDO.getStreet();
        AddressQuery query = new AddressQuery();
        query.setProvinceCode(provinceCode);
        query.setCityCode(cityCode);
        query.setAreaCode(areaCode);
        query.setStreetCode(streetCode);
        JsonResult<AddressDTO> jsonResult = addressApi.queryAddress(query);
        if (!jsonResult.getSuccess() || jsonResult.getData() == null) {
            return orderDeliveryDetailDO.getDetailAddress();
        }

        AddressDTO addressDTO = jsonResult.getData();
        StringBuilder detailAddress = new StringBuilder();
        if (StringUtils.isNotEmpty(addressDTO.getProvince())) {
            detailAddress.append(addressDTO.getProvince());
        }
        if (StringUtils.isNotEmpty(addressDTO.getCity())) {
            detailAddress.append(addressDTO.getCity());
        }
        if (StringUtils.isNotEmpty(addressDTO.getArea())) {
            detailAddress.append(addressDTO.getArea());
        }
        if (StringUtils.isNotEmpty(addressDTO.getStreet())) {
            detailAddress.append(addressDTO.getStreet());
        }
        if (StringUtils.isNotEmpty(orderDeliveryDetailDO.getDetailAddress())) {
            detailAddress.append(orderDeliveryDetailDO.getDetailAddress());
        }
        return detailAddress.toString();
    }

    /**
     * ????????????
     *
     * @param fullOrderData ????????????
     * @param productType   ????????????
     */
    private FullOrderData addNewSubOrder(FullOrderData fullOrderData, Integer productType) {

        // ????????????
        OrderInfoDO orderInfoDO = fullOrderData.getOrderInfoDO();
        // ???????????????
        List<OrderItemDO> orderItemDOList = fullOrderData.getOrderItemDOList();
        // ?????????????????????
        OrderDeliveryDetailDO orderDeliveryDetailDO = fullOrderData.getOrderDeliveryDetailDO();
        // ?????????????????????
        List<OrderPaymentDetailDO> orderPaymentDetailDOList = fullOrderData.getOrderPaymentDetailDOList();
        // ?????????????????????
        List<OrderAmountDO> orderAmountDOList = fullOrderData.getOrderAmountDOList();
        // ?????????????????????
        List<OrderAmountDetailDO> orderAmountDetailDOList = fullOrderData.getOrderAmountDetailDOList();
        // ?????????????????????????????????
        OrderOperateLogDO orderOperateLogDO = fullOrderData.getOrderOperateLogDO();
        // ?????????????????????
        List<OrderSnapshotDO> orderSnapshotDOList = fullOrderData.getOrderSnapshotDOList();


        // ????????????
        String orderId = orderInfoDO.getOrderId();
        // ??????ID
        String userId = orderInfoDO.getUserId();

        // ?????????????????????????????????
        String subOrderId = orderNoManager.genOrderId(OrderNoTypeEnum.SALE_ORDER.getCode(), userId);

        // ????????????????????????
        FullOrderData subFullOrderData = new FullOrderData();

        // ????????????????????????????????????????????????
        List<OrderItemDO> subOrderItemDOList = orderItemDOList.stream()
                .filter(orderItemDO -> productType.equals(orderItemDO.getProductType()))
                .collect(Collectors.toList());

        // ?????????????????????
        Integer subTotalAmount = 0;
        Integer subRealPayAmount = 0;
        for (OrderItemDO subOrderItemDO : subOrderItemDOList) {
            subTotalAmount += subOrderItemDO.getOriginAmount();
            subRealPayAmount += subOrderItemDO.getPayAmount();
        }

        // ???????????????
        OrderInfoDO newSubOrderInfo = orderInfoDO.clone(OrderInfoDO.class);
        newSubOrderInfo.setId(null);
        newSubOrderInfo.setOrderId(subOrderId);
        newSubOrderInfo.setParentOrderId(orderId);
        newSubOrderInfo.setOrderStatus(OrderStatusEnum.INVALID.getCode());
        newSubOrderInfo.setTotalAmount(subTotalAmount);
        newSubOrderInfo.setPayAmount(subRealPayAmount);
        subFullOrderData.setOrderInfoDO(newSubOrderInfo);

        // ????????????
        List<OrderItemDO> newSubOrderItemList = new ArrayList<>();
        for (OrderItemDO orderItemDO : subOrderItemDOList) {
            OrderItemDO newSubOrderItem = orderItemDO.clone(OrderItemDO.class);
            newSubOrderItem.setId(null);
            newSubOrderItem.setOrderId(subOrderId);
            String subOrderItemId = getSubOrderItemId(orderItemDO.getOrderItemId(), subOrderId);
            newSubOrderItem.setOrderItemId(subOrderItemId);
            newSubOrderItemList.add(newSubOrderItem);
        }
        subFullOrderData.setOrderItemDOList(newSubOrderItemList);

        // ????????????????????????
        OrderDeliveryDetailDO newSubOrderDeliveryDetail = orderDeliveryDetailDO.clone(OrderDeliveryDetailDO.class);
        newSubOrderDeliveryDetail.setId(null);
        newSubOrderDeliveryDetail.setOrderId(subOrderId);
        subFullOrderData.setOrderDeliveryDetailDO(newSubOrderDeliveryDetail);


        Map<String, OrderItemDO> subOrderItemMap = subOrderItemDOList.stream()
                .collect(Collectors.toMap(OrderItemDO::getOrderItemId, Function.identity()));

        // ???????????????????????????
        Integer subTotalOriginPayAmount = 0;
        Integer subTotalCouponDiscountAmount = 0;
        Integer subTotalRealPayAmount = 0;

        // ??????????????????
        List<OrderAmountDetailDO> subOrderAmountDetailList = new ArrayList<>();
        for (OrderAmountDetailDO orderAmountDetailDO : orderAmountDetailDOList) {
            String orderItemId = orderAmountDetailDO.getOrderItemId();
            if (!subOrderItemMap.containsKey(orderItemId)) {
                continue;
            }
            OrderAmountDetailDO subOrderAmountDetail = orderAmountDetailDO.clone(OrderAmountDetailDO.class);
            subOrderAmountDetail.setId(null);
            subOrderAmountDetail.setOrderId(subOrderId);
            String subOrderItemId = getSubOrderItemId(orderItemId, subOrderId);
            subOrderAmountDetail.setOrderItemId(subOrderItemId);
            subOrderAmountDetailList.add(subOrderAmountDetail);

            Integer amountType = orderAmountDetailDO.getAmountType();
            Integer amount = orderAmountDetailDO.getAmount();
            if (AmountTypeEnum.ORIGIN_PAY_AMOUNT.getCode().equals(amountType)) {
                subTotalOriginPayAmount += amount;
            }
            if (AmountTypeEnum.COUPON_DISCOUNT_AMOUNT.getCode().equals(amountType)) {
                subTotalCouponDiscountAmount += amount;
            }
            if (AmountTypeEnum.REAL_PAY_AMOUNT.getCode().equals(amountType)) {
                subTotalRealPayAmount += amount;
            }
        }
        subFullOrderData.setOrderAmountDetailDOList(subOrderAmountDetailList);

        // ??????????????????
        List<OrderAmountDO> subOrderAmountList = new ArrayList<>();
        for (OrderAmountDO orderAmountDO : orderAmountDOList) {
            Integer amountType = orderAmountDO.getAmountType();
            OrderAmountDO subOrderAmount = orderAmountDO.clone(OrderAmountDO.class);
            subOrderAmount.setId(null);
            subOrderAmount.setOrderId(subOrderId);
            if (AmountTypeEnum.ORIGIN_PAY_AMOUNT.getCode().equals(amountType)) {
                subOrderAmount.setAmount(subTotalOriginPayAmount);
                subOrderAmountList.add(subOrderAmount);
            }
            if (AmountTypeEnum.COUPON_DISCOUNT_AMOUNT.getCode().equals(amountType)) {
                subOrderAmount.setAmount(subTotalCouponDiscountAmount);
                subOrderAmountList.add(subOrderAmount);
            }
            if (AmountTypeEnum.REAL_PAY_AMOUNT.getCode().equals(amountType)) {
                subOrderAmount.setAmount(subTotalRealPayAmount);
                subOrderAmountList.add(subOrderAmount);
            }
        }
        subFullOrderData.setOrderAmountDOList(subOrderAmountList);

        // ??????????????????
        List<OrderPaymentDetailDO> subOrderPaymentDetailDOList = new ArrayList<>();
        for (OrderPaymentDetailDO orderPaymentDetailDO : orderPaymentDetailDOList) {
            OrderPaymentDetailDO subOrderPaymentDetail = orderPaymentDetailDO.clone(OrderPaymentDetailDO.class);
            subOrderPaymentDetail.setId(null);
            subOrderPaymentDetail.setOrderId(subOrderId);
            subOrderPaymentDetail.setPayAmount(subTotalRealPayAmount);
            subOrderPaymentDetailDOList.add(subOrderPaymentDetail);
        }
        subFullOrderData.setOrderPaymentDetailDOList(subOrderPaymentDetailDOList);

        // ??????????????????????????????
        OrderOperateLogDO subOrderOperateLogDO = orderOperateLogDO.clone(OrderOperateLogDO.class);
        subOrderOperateLogDO.setId(null);
        subOrderOperateLogDO.setOrderId(subOrderId);
        subFullOrderData.setOrderOperateLogDO(subOrderOperateLogDO);

        // ????????????????????????
        List<OrderSnapshotDO> subOrderSnapshotDOList = new ArrayList<>();
        for (OrderSnapshotDO orderSnapshotDO : orderSnapshotDOList) {
            OrderSnapshotDO subOrderSnapshotDO = orderSnapshotDO.clone(OrderSnapshotDO.class);
            subOrderSnapshotDO.setId(null);
            subOrderSnapshotDO.setOrderId(subOrderId);
            if (SnapshotTypeEnum.ORDER_AMOUNT.getCode().equals(orderSnapshotDO.getSnapshotType())) {
                subOrderSnapshotDO.setSnapshotJson(JsonUtil.object2Json(subOrderAmountList));
            } else if (SnapshotTypeEnum.ORDER_ITEM.getCode().equals(orderSnapshotDO.getSnapshotType())) {
                subOrderSnapshotDO.setSnapshotJson(JsonUtil.object2Json(subOrderItemDOList));
            }
            subOrderSnapshotDOList.add(subOrderSnapshotDO);
        }
        subFullOrderData.setOrderSnapshotDOList(subOrderSnapshotDOList);
        return subFullOrderData;
    }

    /**
     * ??????????????????orderItemId???
     */
    private String getSubOrderItemId(String orderItemId, String subOrderId) {
        String postfix = orderItemId.substring(orderItemId.indexOf("_"));
        return subOrderId + postfix;
    }

    /**
     * ?????????????????????????????????????????????????????????????????????
     */
    private void sendPayOrderTimeoutDelayMessage(CreateOrderRequest createOrderRequest) {
        PayOrderTimeoutDelayMessage message = new PayOrderTimeoutDelayMessage();

        message.setOrderId(createOrderRequest.getOrderId());
        message.setBusinessIdentifier(createOrderRequest.getBusinessIdentifier());
        message.setCancelType(OrderCancelTypeEnum.TIMEOUT_CANCELED.getCode());
        message.setUserId(createOrderRequest.getUserId());
        message.setOrderType(createOrderRequest.getOrderType());
        message.setOrderStatus(OrderStatusEnum.CREATED.getCode());

        String msgJson = JsonUtil.object2Json(message);
        defaultProducer.sendMessage(RocketMqConstant.PAY_ORDER_TIMEOUT_DELAY_TOPIC, msgJson,
                RocketDelayedLevel.DELAYED_30m, "??????????????????????????????");
    }

    /**
     * ???????????????
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public PrePayOrderDTO prePayOrder(PrePayOrderRequest prePayOrderRequest) {
        // ????????????
        checkPrePayOrderRequestParam(prePayOrderRequest);

        String orderId = prePayOrderRequest.getOrderId();
        Integer payAmount = prePayOrderRequest.getPayAmount();

        // ??????????????????????????????????????????????????????????????????
        String key = RedisLockKeyConstants.ORDER_PAY_KEY + orderId;
        boolean lock = redisLock.lock(key);
        if (!lock) {
            throw new OrderBizException(OrderErrorCodeEnum.ORDER_PRE_PAY_ERROR);
        }
        try {
            // ???????????????????????????
            checkPrePayOrderInfo(orderId, payAmount);

            // ?????????????????????????????????
            PayOrderRequest payOrderRequest = prePayOrderRequest.clone(PayOrderRequest.class);
            JsonResult<PayOrderDTO> jsonResult = payApi.payOrder(payOrderRequest);
            if (!jsonResult.getSuccess()) {
                throw new OrderBizException(OrderErrorCodeEnum.ORDER_PRE_PAY_ERROR);
            }
            PayOrderDTO payOrderDTO = jsonResult.getData();

            // ?????????????????????????????????
            updateOrderPaymentInfo(payOrderDTO);

            return payOrderDTO.clone(PrePayOrderDTO.class);
        } finally {
            // ??????????????????
            redisLock.unlock(key);
        }
    }

    /**
     * ??????????????????????????????
     * @param orderId
     * @param payAmount
     */
    private void checkPrePayOrderInfo(String orderId, Integer payAmount) {
        // ??????????????????
        OrderInfoDO orderInfoDO = orderInfoDAO.getByOrderId(orderId);
        OrderPaymentDetailDO orderPaymentDetailDO = orderPaymentDetailDAO.getPaymentDetailByOrderId(orderId);
        if (orderInfoDO == null || orderPaymentDetailDO == null) {
            throw new OrderBizException(OrderErrorCodeEnum.ORDER_INFO_IS_NULL);
        }

        // ????????????????????????
        if (!payAmount.equals(orderInfoDO.getPayAmount())) {
            throw new OrderBizException(OrderErrorCodeEnum.ORDER_PAY_AMOUNT_ERROR);
        }

        // ????????????????????????
        if (!OrderStatusEnum.CREATED.getCode().equals(orderInfoDO.getOrderStatus())) {
            throw new OrderBizException(OrderErrorCodeEnum.ORDER_STATUS_ERROR);
        }

        // ????????????????????????
        if (PayStatusEnum.PAID.getCode().equals(orderPaymentDetailDO.getPayStatus())) {
            throw new OrderBizException(OrderErrorCodeEnum.ORDER_PAY_STATUS_IS_PAID);
        }

        // ???????????????????????????????????????
        Date curDate = new Date();
        if(curDate.after(orderInfoDO.getExpireTime())) {
            throw new OrderBizException(OrderErrorCodeEnum.ORDER_PRE_PAY_EXPIRE_ERROR);
        }
    }

    /**
     * ???????????????????????????
     */
    private void checkPrePayOrderRequestParam(PrePayOrderRequest prePayOrderRequest) {

        String userId = prePayOrderRequest.getUserId();
        ParamCheckUtil.checkStringNonEmpty(userId, OrderErrorCodeEnum.USER_ID_IS_NULL);

        String businessIdentifier = prePayOrderRequest.getBusinessIdentifier();
        ParamCheckUtil.checkStringNonEmpty(businessIdentifier, OrderErrorCodeEnum.BUSINESS_IDENTIFIER_ERROR);

        Integer payType = prePayOrderRequest.getPayType();
        ParamCheckUtil.checkObjectNonNull(payType, OrderErrorCodeEnum.PAY_TYPE_PARAM_ERROR);
        if (PayTypeEnum.getByCode(payType) == null) {
            throw new OrderBizException(OrderErrorCodeEnum.PAY_TYPE_PARAM_ERROR);
        }

        String orderId = prePayOrderRequest.getOrderId();
        ParamCheckUtil.checkStringNonEmpty(orderId, OrderErrorCodeEnum.ORDER_ID_IS_NULL);

        Integer payAmount = prePayOrderRequest.getPayAmount();
        ParamCheckUtil.checkObjectNonNull(payAmount, OrderErrorCodeEnum.PAY_TYPE_PARAM_ERROR);
    }

    /**
     * ?????????????????????????????????
     */
    private void updateOrderPaymentInfo(PayOrderDTO payOrderDTO) {
        String orderId = payOrderDTO.getOrderId();
        Integer payType = payOrderDTO.getPayType();
        String outTradeNo = payOrderDTO.getOutTradeNo();
        Date payTime = new Date();

        // ?????????????????????
        OrderInfoDO orderInfoDO = orderInfoDAO.getByOrderId(orderId);
        orderInfoDO.setPayType(payType);
        orderInfoDO.setPayTime(payTime);
        orderInfoDAO.updateById(orderInfoDO);

        // ??????????????????
        OrderPaymentDetailDO orderPaymentDetailDO = orderPaymentDetailDAO.getPaymentDetailByOrderId(orderId);
        orderPaymentDetailDO.setPayTime(payTime);
        orderPaymentDetailDO.setPayType(payType);
        orderPaymentDetailDO.setOutTradeNo(outTradeNo);
        orderPaymentDetailDAO.updateById(orderPaymentDetailDO);

        // ???????????????????????????
        List<OrderInfoDO> subOrderInfoList = orderInfoDAO.listByParentOrderId(orderId);
        if (subOrderInfoList != null && !subOrderInfoList.isEmpty()) {
            for (OrderInfoDO subOrderInfoDO : subOrderInfoList) {

                // ???????????????????????????
                subOrderInfoDO.setPayType(payType);
                subOrderInfoDO.setPayTime(payTime);
                orderInfoDAO.updateById(subOrderInfoDO);

                // ?????????????????????????????????
                OrderPaymentDetailDO subOrderPaymentDetailDO =
                        orderPaymentDetailDAO.getPaymentDetailByOrderId(subOrderInfoDO.getOrderId());
                subOrderPaymentDetailDO.setPayTime(payTime);
                subOrderPaymentDetailDO.setPayType(payType);
                subOrderPaymentDetailDO.setOutTradeNo(outTradeNo);
                orderPaymentDetailDAO.updateById(subOrderPaymentDetailDO);
            }
        }

    }

    /**
     * ????????????
     */
    @Transactional(rollbackFor = Exception.class)
    @Override
    public void payCallback(PayCallbackRequest payCallbackRequest) {
        // ????????????
        checkPayCallbackRequestParam(payCallbackRequest);

        String orderId = payCallbackRequest.getOrderId();
        Integer payAmount = payCallbackRequest.getPayAmount();
        Integer payType = payCallbackRequest.getPayType();

        // ???????????????????????????????????????????????????
        String key = RedisLockKeyConstants.ORDER_PAY_KEY + orderId;
        boolean lock = redisLock.lock(key);
        if (!lock) {
            throw new OrderBizException(OrderErrorCodeEnum.ORDER_PAY_CALLBACK_ERROR);
        }

        try {
            // ??????????????????????????????????????????
            OrderInfoDO orderInfoDO = orderInfoDAO.getByOrderId(orderId);
            OrderPaymentDetailDO orderPaymentDetailDO = orderPaymentDetailDAO.getPaymentDetailByOrderId(orderId);

            // ????????????
            if (orderInfoDO == null || orderPaymentDetailDO == null) {
                throw new OrderBizException(OrderErrorCodeEnum.ORDER_INFO_IS_NULL);
            }
            if (!payAmount.equals(orderInfoDO.getPayAmount())) {
                throw new OrderBizException(OrderErrorCodeEnum.ORDER_CALLBACK_PAY_AMOUNT_ERROR);
            }

            // ??????????????????
            Integer orderStatus = orderInfoDO.getOrderStatus();
            if (OrderStatusEnum.CREATED.getCode().equals(orderStatus)) {
                // ????????????????????? "?????????"???????????????????????????????????????
                updateOrderStatusPaid(payCallbackRequest, orderInfoDO, orderPaymentDetailDO);

                // ?????? "?????????????????????" ??????
                sendPaidOrderSuccessMessage(orderInfoDO);
            } else {
                // ???????????????????????? "?????????"
                if (OrderStatusEnum.CANCELED.getCode().equals(orderStatus)) {
                    // ????????????????????????????????????
                    Integer payStatus = orderPaymentDetailDO.getPayStatus();
                    if (PayStatusEnum.UNPAID.getCode().equals(payStatus)) {
                        // ????????????
                        executeOrderRefund(orderInfoDO, orderPaymentDetailDO);
                        throw new OrderBizException(OrderErrorCodeEnum.ORDER_CANCEL_PAY_CALLBACK_ERROR);
                    } else if (PayStatusEnum.PAID.getCode().equals(payStatus)) {
                        if (payType.equals(orderPaymentDetailDO.getPayType())) {
                            throw new OrderBizException(OrderErrorCodeEnum.ORDER_CANCEL_PAY_CALLBACK_PAY_TYPE_SAME_ERROR);
                        } else {
                            throw new OrderBizException(OrderErrorCodeEnum.ORDER_CANCEL_PAY_CALLBACK_PAY_TYPE_NO_SAME_ERROR);
                        }
                    }
                } else {
                    // ????????????????????????????????????
                    if (PayStatusEnum.PAID.getCode().equals(orderPaymentDetailDO.getPayStatus())) {
                        if (payType.equals(orderPaymentDetailDO.getPayType())) {
                            return;
                        }
                        // ????????????
                        executeOrderRefund(orderInfoDO, orderPaymentDetailDO);
                        throw new OrderBizException(OrderErrorCodeEnum.ORDER_CANCEL_PAY_CALLBACK_REPEAT_ERROR);
                    }
                }
            }

        } finally {
            // ??????????????????
            redisLock.unlock(key);
        }
    }

    /**
     * ??????????????????
     */
    private void executeOrderRefund(OrderInfoDO orderInfoDO, OrderPaymentDetailDO orderPaymentDetailDO) {
        PayRefundRequest payRefundRequest = new PayRefundRequest();
        payRefundRequest.setOrderId(orderInfoDO.getOrderId());
        payRefundRequest.setRefundAmount(orderPaymentDetailDO.getPayAmount());
        payRefundRequest.setOutTradeNo(orderPaymentDetailDO.getOutTradeNo());
        payApi.executeRefund(payRefundRequest);
    }

    /**
     * ????????????????????????????????????
     */
    private void checkPayCallbackRequestParam(PayCallbackRequest payCallbackRequest) {
        ParamCheckUtil.checkObjectNonNull(payCallbackRequest);

        // ?????????
        String orderId = payCallbackRequest.getOrderId();
        ParamCheckUtil.checkStringNonEmpty(orderId);

        // ????????????
        Integer payAmount = payCallbackRequest.getPayAmount();
        ParamCheckUtil.checkObjectNonNull(payAmount);

        // ???????????????????????????
        String outTradeNo = payCallbackRequest.getOutTradeNo();
        ParamCheckUtil.checkStringNonEmpty(outTradeNo);

        // ????????????
        Integer payType = payCallbackRequest.getPayType();
        ParamCheckUtil.checkObjectNonNull(payType);
        if (PayTypeEnum.getByCode(payType) == null) {
            throw new OrderBizException(OrderErrorCodeEnum.PAY_TYPE_PARAM_ERROR);
        }

        // ??????ID
        String merchantId = payCallbackRequest.getMerchantId();
        ParamCheckUtil.checkStringNonEmpty(merchantId);
    }

    /**
     * ????????????????????? ?????????
     */
    private void updateOrderStatusPaid(PayCallbackRequest payCallbackRequest,
                                       OrderInfoDO orderInfoDO,
                                       OrderPaymentDetailDO orderPaymentDetailDO) {
        // ????????????
        String orderId = payCallbackRequest.getOrderId();
        Integer preOrderStatus = orderInfoDO.getOrderStatus();
        orderInfoDO.setOrderStatus(OrderStatusEnum.PAID.getCode());
        orderInfoDAO.updateById(orderInfoDO);

        // ??????????????????
        orderPaymentDetailDO.setPayStatus(PayStatusEnum.PAID.getCode());
        orderPaymentDetailDAO.updateById(orderPaymentDetailDO);

        // ??????????????????????????????
        OrderOperateLogDO orderOperateLogDO = new OrderOperateLogDO();
        orderOperateLogDO.setOrderId(orderId);
        orderOperateLogDO.setOperateType(OrderOperateTypeEnum.PAID_ORDER.getCode());
        orderOperateLogDO.setPreStatus(preOrderStatus);
        orderOperateLogDO.setCurrentStatus(orderInfoDO.getOrderStatus());
        orderOperateLogDO.setRemark("????????????????????????"
                + orderOperateLogDO.getPreStatus() + "-"
                + orderOperateLogDO.getCurrentStatus());
        orderOperateLogDAO.save(orderOperateLogDO);

        // ???????????????????????????
        List<OrderInfoDO> subOrderInfoDOList = orderInfoDAO.listByParentOrderId(orderId);
        if (subOrderInfoDOList != null && !subOrderInfoDOList.isEmpty()) {
            // ??????????????????????????????????????????
            Integer newPreOrderStatus = orderInfoDO.getOrderStatus();
            orderInfoDO.setOrderStatus(OrderStatusEnum.INVALID.getCode());
            orderInfoDAO.updateById(orderInfoDO);

            // ??????????????????????????????
            OrderOperateLogDO newOrderOperateLogDO = new OrderOperateLogDO();
            newOrderOperateLogDO.setOrderId(orderId);
            newOrderOperateLogDO.setOperateType(OrderOperateTypeEnum.PAID_ORDER.getCode());
            newOrderOperateLogDO.setPreStatus(newPreOrderStatus);
            newOrderOperateLogDO.setCurrentStatus(OrderStatusEnum.INVALID.getCode());
            orderOperateLogDO.setRemark("????????????????????????????????????????????????"
                    + newOrderOperateLogDO.getPreStatus() + "-"
                    + newOrderOperateLogDO.getCurrentStatus());
            orderOperateLogDAO.save(newOrderOperateLogDO);

            // ???????????????????????????
            for (OrderInfoDO subOrderInfo : subOrderInfoDOList) {
                Integer subPreOrderStatus = subOrderInfo.getOrderStatus();
                subOrderInfo.setOrderStatus(OrderStatusEnum.PAID.getCode());
                orderInfoDAO.updateById(subOrderInfo);

                // ????????????????????????????????????
                String subOrderId = subOrderInfo.getOrderId();
                OrderPaymentDetailDO subOrderPaymentDetailDO =
                        orderPaymentDetailDAO.getPaymentDetailByOrderId(subOrderId);
                if (subOrderPaymentDetailDO != null) {
                    subOrderPaymentDetailDO.setPayStatus(PayStatusEnum.PAID.getCode());
                    orderPaymentDetailDAO.updateById(subOrderPaymentDetailDO);
                }

                // ??????????????????????????????
                OrderOperateLogDO subOrderOperateLogDO = new OrderOperateLogDO();
                subOrderOperateLogDO.setOrderId(subOrderId);
                subOrderOperateLogDO.setOperateType(OrderOperateTypeEnum.PAID_ORDER.getCode());
                subOrderOperateLogDO.setPreStatus(subPreOrderStatus);
                subOrderOperateLogDO.setCurrentStatus(OrderStatusEnum.PAID.getCode());
                orderOperateLogDO.setRemark("????????????????????????????????????????????????"
                        + subOrderOperateLogDO.getPreStatus() + "-"
                        + subOrderOperateLogDO.getCurrentStatus());
                orderOperateLogDAO.save(subOrderOperateLogDO);
            }
        }

    }

    /**
     * ????????????????????????????????????????????????????????????
     */
    private void sendPaidOrderSuccessMessage(OrderInfoDO orderInfoDO) {
        String orderId = orderInfoDO.getOrderId();
        PaidOrderSuccessMessage message = new PaidOrderSuccessMessage();
        message.setOrderId(orderId);
        String msgJson = JSON.toJSONString(message);
        defaultProducer.sendMessage(RocketMqConstant.PAID_ORDER_SUCCESS_TOPIC, msgJson, "?????????????????????");
    }

    @Override
    public boolean removeOrders(List<String> orderIds) {
        //1?????????id????????????
        List<OrderInfoDO> orders = orderInfoDAO.listByOrderIds(orderIds);
        if (CollectionUtils.isEmpty(orders)) {
            return true;
        }

        //2?????????????????????????????????
        orders.forEach(order -> {
            if (!canRemove(order)) {
                throw new OrderBizException(OrderErrorCodeEnum.ORDER_CANNOT_REMOVE);
            }
        });

        //3???????????????????????????
        List<Long> ids = orders.stream().map(OrderInfoDO::getId).collect(Collectors.toList());
        orderInfoDAO.softRemoveOrders(ids);

        return true;
    }

    private boolean canRemove(OrderInfoDO order) {
        return OrderStatusEnum.canRemoveStatus().contains(order.getOrderStatus()) &&
                DeleteStatusEnum.NO.getCode().equals(order.getDeleteStatus());
    }

    @Override
    public boolean adjustDeliveryAddress(AdjustDeliveryAddressRequest request) {
        //1?????????id????????????
        OrderInfoDO order = orderInfoDAO.getByOrderId(request.getOrderId());
        ParamCheckUtil.checkObjectNonNull(order, OrderErrorCodeEnum.ORDER_NOT_FOUND);

        //2??????????????????????????????
        if (!OrderStatusEnum.unOutStockStatus().contains(order.getOrderStatus())) {
            throw new OrderBizException(OrderErrorCodeEnum.ORDER_NOT_ALLOW_TO_ADJUST_ADDRESS);
        }

        //3???????????????????????????
        OrderDeliveryDetailDO orderDeliveryDetail = orderDeliveryDetailDAO.getByOrderId(request.getOrderId());
        if (null == orderDeliveryDetail) {
            throw new OrderBizException(OrderErrorCodeEnum.ORDER_DELIVERY_NOT_FOUND);
        }

        //4???????????????????????????????????????????????????
        if (orderDeliveryDetail.getModifyAddressCount() > 0) {
            throw new OrderBizException(OrderErrorCodeEnum.ORDER_DELIVERY_ADDRESS_HAS_BEEN_ADJUSTED);
        }

        //5???????????????????????????
        orderDeliveryDetailDAO.updateDeliveryAddress(orderDeliveryDetail.getId()
                , orderDeliveryDetail.getModifyAddressCount(), request);

        return true;
    }
}