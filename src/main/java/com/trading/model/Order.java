package com.trading.model;

import java.time.LocalDateTime;

public class Order {

    public enum OrderType    { MARKET, LIMIT, STOPLOSS_MARKET, STOPLOSS_LIMIT }
    public enum OrderSide    { BUY, SELL }
    public enum OrderStatus  { PENDING, OPEN, COMPLETE, REJECTED, CANCELLED }
    public enum ProductType  { INTRADAY, DELIVERY }

    private final String      orderId;
    private final String      symbol;
    private final String      token;
    private final String      exchange;
    private final OrderSide   side;
    private final OrderType   type;
    private final ProductType productType;
    private final int         quantity;
    private final double      price;
    private final double      triggerPrice;
    private       OrderStatus status;
    private       int         filledQty;
    private final LocalDateTime placedAt;
    private       LocalDateTime updatedAt;

    public Order(String orderId, String symbol, String token, String exchange,
                 OrderSide side, OrderType type, ProductType productType,
                 int quantity, double price, double triggerPrice) {
        this.orderId      = orderId;
        this.symbol       = symbol;
        this.token        = token;
        this.exchange     = exchange;
        this.side         = side;
        this.type         = type;
        this.productType  = productType;
        this.quantity     = quantity;
        this.price        = price;
        this.triggerPrice = triggerPrice;
        this.status       = OrderStatus.PENDING;
        this.filledQty    = 0;
        this.placedAt     = LocalDateTime.now();
        this.updatedAt    = placedAt;
    }

    public void setStatus(OrderStatus status) {
        this.status     = status;
        this.updatedAt  = LocalDateTime.now();
    }

    public void setFilledQty(int filledQty) { this.filledQty = filledQty; }

    public String      getOrderId()      { return orderId; }
    public String      getSymbol()       { return symbol; }
    public String      getToken()        { return token; }
    public String      getExchange()     { return exchange; }
    public OrderSide   getSide()         { return side; }
    public OrderType   getType()         { return type; }
    public ProductType getProductType()  { return productType; }
    public int         getQuantity()     { return quantity; }
    public double      getPrice()        { return price; }
    public double      getTriggerPrice() { return triggerPrice; }
    public OrderStatus getStatus()       { return status; }
    public int         getFilledQty()    { return filledQty; }
    public LocalDateTime getPlacedAt()  { return placedAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }

    public boolean isComplete()   { return status == OrderStatus.COMPLETE; }
    public boolean isCancelled()  { return status == OrderStatus.CANCELLED; }
    public boolean isRejected()   { return status == OrderStatus.REJECTED; }
    public boolean isPending()    { return status == OrderStatus.PENDING || status == OrderStatus.OPEN; }

    @Override
    public String toString() {
        return "Order[%s %s %s x%d @%.2f status=%s]"
                .formatted(orderId, side, symbol, quantity, price, status);
    }
}
