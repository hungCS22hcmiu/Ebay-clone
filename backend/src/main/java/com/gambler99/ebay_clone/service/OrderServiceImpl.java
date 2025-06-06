package com.gambler99.ebay_clone.service;

import com.gambler99.ebay_clone.dto.OrderItemDTO;
import com.gambler99.ebay_clone.dto.OrderResponseDTO;
import com.gambler99.ebay_clone.entity.*;
import com.gambler99.ebay_clone.exception.OrderException;
import com.gambler99.ebay_clone.repository.*;
import org.springframework.stereotype.Service;

import jakarta.transaction.Transactional;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@Transactional
public class OrderServiceImpl implements OrderService {

    private final OrderRepository orderRepository;
    private final CartItemRepository cartItemRepository;
    private final ProductRepository productRepository;
    private final UserRepository userRepository;
    private final AuctionRepository auctionRepository;

    public OrderServiceImpl(OrderRepository orderRepository, CartItemRepository cartItemRepository,
                            ProductRepository productRepository, UserRepository userRepository,
                            AuctionRepository auctionRepository) {
        this.orderRepository = orderRepository;
        this.cartItemRepository = cartItemRepository;
        this.productRepository = productRepository;
        this.userRepository = userRepository;
        this.auctionRepository = auctionRepository;
    }

    @Override
    public OrderResponseDTO createOrderFromCart(Long userId, List<Long> productIds) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new OrderException("User not found with ID: " + userId));

        List<CartItem> cartItems = cartItemRepository.findByUserUserId(userId);

        if (productIds != null && !productIds.isEmpty()) {
            cartItems = cartItems.stream()
                    .filter(cartItem -> productIds.contains(cartItem.getProduct().getProductId()))
                    .collect(Collectors.toList());
        }

        if (cartItems.isEmpty()) {
            throw new OrderException("No valid cart items found for user ID: " + userId);
        }

        Order order = new Order();
        order.setCustomer(user);
        order.setOrderDate(LocalDateTime.now());
        order.setStatus(Order.OrderStatus.PENDING_PAYMENT);

        for (CartItem cartItem : cartItems) {
            Product product = cartItem.getProduct();

            if (product.getStockQuantity() < cartItem.getQuantity()) {
                throw new OrderException("Insufficient stock for product: " + product.getName());
            }

            OrderItem orderItem = OrderItem.builder()
                    .order(order)
                    .product(product)
                    .quantity(cartItem.getQuantity())
                    // .priceAtPurchase(product.getPrice())
                    .priceAtPurchase(product.getPrice().multiply(BigDecimal.valueOf(cartItem.getQuantity())))

                    .build();

            order.getOrderItems().add(orderItem);
            order.setShippingAddressSnapshot(user.getAddress());
            order.setBillingAddressSnapshot(user.getAddress());

            // Update stock quantity
            product.setStockQuantity(product.getStockQuantity() - cartItem.getQuantity());
            productRepository.save(product);
        }

        // Calculate total amount
        order.calculateTotalAmount();

        // Save order and delete cart items
        Order savedOrder = orderRepository.save(order);
        cartItemRepository.deleteAll(cartItems);

        return mapToResponseDTO(savedOrder);
    }

    @Override
    public OrderResponseDTO createOrderFromAllCartItems(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new OrderException("User not found with ID: " + userId));

        List<CartItem> cartItems = cartItemRepository.findByUserUserId(userId);

        if (cartItems.isEmpty()) {
            throw new OrderException("No cart items found for user ID: " + userId);
        }

        Order order = new Order();
        order.setCustomer(user);
        order.setOrderDate(LocalDateTime.now());
        order.setStatus(Order.OrderStatus.PENDING_PAYMENT);

        for (CartItem cartItem : cartItems) {
            Product product = cartItem.getProduct();

            if (product.getStockQuantity() < cartItem.getQuantity()) {
                throw new OrderException("Insufficient stock for product: " + product.getName());
            }

            OrderItem orderItem = OrderItem.builder()
                    .order(order)
                    .product(product)
                    .quantity(cartItem.getQuantity())
                    .priceAtPurchase(product.getPrice().multiply(BigDecimal.valueOf(cartItem.getQuantity())))
                    .build();

            order.getOrderItems().add(orderItem);
            order.setShippingAddressSnapshot(user.getAddress());
            order.setBillingAddressSnapshot(user.getAddress());
           // order.setCustomerName(user.getUsername());

            // Update stock quantity
            product.setStockQuantity(product.getStockQuantity() - cartItem.getQuantity());
            productRepository.save(product);
        }

        // Calculate total amount
        order.calculateTotalAmount();

        // Save order and delete cart items
        Order savedOrder = orderRepository.save(order);
        cartItemRepository.deleteAll(cartItems);

        return mapToResponseDTO(savedOrder);
    }

    @Override
    public void deleteOrder(Long orderId, Long userId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new OrderException("Order not found with ID: " + orderId));
        // Check if the order belongs to the customer
        if (!order.getCustomer().getUserId().equals(userId)) {
            throw new OrderException("Unauthorized action for user ID: " + userId);
        }
        // Check if the order is in PENDING_PAYMENT status
        if (order.getStatus() != Order.OrderStatus.PENDING_PAYMENT) {
            throw new OrderException("Cannot delete order unless it is in PENDING_PAYMENT status.");
        }
        // check if order is from auction then prevent deletion
        if (order.isAuctionOrder()) {
            throw new OrderException("Cannot delete order containing auction items.");
        }
        // Restore stock quantities
        for (OrderItem orderItem : order.getOrderItems()) {
            Product product = orderItem.getProduct();
            product.setStockQuantity(product.getStockQuantity() + orderItem.getQuantity());
            productRepository.save(product);
        }

        orderRepository.delete(order);
    }

    @Override
    public List<OrderResponseDTO> getAllOrdersForCustomer(Long userId) {
        // Fetch all orders for the customer
        List<Order> orders = orderRepository.findByCustomerUserId(userId);

        // Map orders to OrderResponseDTO
        return orders.stream()
                .map(this::mapToResponseDTO)
                .collect(Collectors.toList());
    }

    @Override
    public OrderResponseDTO getOrderById(Long orderId, Long userId) {
        // Fetch the order by ID
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new OrderException("Order not found with ID: " + orderId));

        // Check if the order belongs to the customer
        if (!order.getCustomer().getUserId().equals(userId)) {
            throw new OrderException("Unauthorized action for user ID: " + userId);
        }

        // Map the order to OrderResponseDTO
        return mapToResponseDTO(order);
    }

    // auction products into order
    @Override
    public OrderResponseDTO createOrderFromAuctionItems(Long userId, Long auctionId, BigDecimal winningBidPrice) { // productId dc dang len auction nhung ma code dang auctionId
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new OrderException("User not found with ID: " + userId));
        
        Auction auction = auctionRepository.findById(auctionId)
                .orElseThrow(() -> new OrderException("Auction not found with ID: " + auctionId));
        Product product = auction.getProduct();
        if (product == null) {
            throw new OrderException("Auction product not found with ID: " + auctionId);
        }
        if (product.getStockQuantity() <= 0) {
            throw new OrderException("Product is out of stock: " + product.getName());
        }
        Order order = new Order();
        order.setCustomer(user);
        order.setOrderDate(LocalDateTime.now());
        order.setStatus(Order.OrderStatus.PENDING_PAYMENT);
        order.setAuctionOrder(true); // Mark this order as an auction order

        OrderItem orderItem = OrderItem.builder()
                .order(order)
                .product(product)
                .quantity(1) // Assuming quantity is 1 for auction items
                .priceAtPurchase(winningBidPrice) // Use the winning bid price
                .build();

        order.getOrderItems().add(orderItem); // null point exception
        order.calculateTotalAmount();
        order.setShippingAddressSnapshot(user.getAddress());
        order.setBillingAddressSnapshot(user.getAddress());
        // Update stock quantity
        product.setStockQuantity(product.getStockQuantity() - 1);
        productRepository.save(product);
        // Save order
        Order savedOrder = orderRepository.save(order);
        // Return the response DTO
        return mapToResponseDTO(savedOrder);
    }

    // Ensure this method is present and correctly named
    private OrderResponseDTO mapToResponseDTO(Order order) {
        return OrderResponseDTO.builder()
                .orderId(order.getOrderId())
                .customerId(order.getCustomer().getUserId())
                .customerName(order.getCustomer().getUsername()) 
                .orderItems(order.getOrderItems().stream()
                        .map(orderItem -> OrderItemDTO.builder()
                                .orderItemId(orderItem.getOrderItemId())
                                .productId(orderItem.getProduct().getProductId())
                                .productName(orderItem.getProduct().getName())
                                .quantity(orderItem.getQuantity())
                                .priceAtPurchase(orderItem.getPriceAtPurchase().doubleValue())
                                .productImageUrl(orderItem.getProduct().getImageUrl())
                                .build())
                        .collect(Collectors.toList()))
                .orderDate(order.getOrderDate())
                .status(order.getStatus())
                .totalAmount(order.getTotalAmount())

                .shippingAddressSnapshot(order.getShippingAddressSnapshot())
                .billingAddressSnapshot(order.getBillingAddressSnapshot())
                
                .build();
    }
}

