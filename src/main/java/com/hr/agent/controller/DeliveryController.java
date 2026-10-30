package com.hr.agent.controller;

import com.hr.agent.service.DeliveryService;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * 配送单管理 API：
 * - 管理员：创建配送单、查询全部、确认出库、编辑、取消
 * - 配送员：查询自己的配送单、确认送达
 * - 权限隔离：/api/delivery/my/** 仅配送员本人或管理员可访问
 */
@RestController
@RequestMapping("/api/delivery")
public class DeliveryController {

    private final DeliveryService deliveryService;

    public DeliveryController(DeliveryService deliveryService) {
        this.deliveryService = deliveryService;
    }

    // ==================== 管理员：配送单 CRUD ====================

    /** 创建配送单（管理员下单，不动库存） */
    @PostMapping
    public Map<String, Object> create(@RequestBody CreateForm f) {
        if (f.orderId() == null) throw new IllegalArgumentException("关联订单不能为空");
        String deliveryNo = deliveryService.createDelivery(
                f.orderId(), f.courierId(), f.warehouseId(),
                f.address(), f.destination(),
                f.fromLng(), f.fromLat(), f.toLng(), f.toLat(),
                f.deadline(), f.remark(), f.customerPhone());
        return Map.of("ok", true, "deliveryNo", deliveryNo);
    }

    /** 查询配送单列表（管理员看全部） */
    @GetMapping
    public List<Map<String, Object>> list(@RequestParam(required = false) String status,
                                          @RequestParam(defaultValue = "200") int limit) {
        return deliveryService.listAll(status, limit);
    }

    /** 查询配送单详情 */
    @GetMapping("/{deliveryNo}")
    public Map<String, Object> detail(@PathVariable String deliveryNo) {
        Map<String, Object> detail = deliveryService.getDetail(deliveryNo);
        if (detail == null) throw new IllegalArgumentException("配送单不存在：" + deliveryNo);
        return detail;
    }

    /** 确认出库（扣库存 + 生成出库单，状态→配送中） */
    @PostMapping("/{deliveryNo}/confirm-outbound")
    public Map<String, Object> confirmOutbound(@PathVariable String deliveryNo) {
        String outboundNo = deliveryService.confirmOutbound(deliveryNo);
        return Map.of("ok", true, "outboundNo", outboundNo);
    }

    /** 取消配送单（仅待配送状态可取消） */
    @PostMapping("/{deliveryNo}/cancel")
    public Map<String, Object> cancel(@PathVariable String deliveryNo) {
        deliveryService.cancelDelivery(deliveryNo);
        return Map.of("ok", true);
    }

    /** 检查库存是否充足 */
    @GetMapping("/check-stock")
    public Map<String, Object> checkStock(@RequestParam Long warehouseId,
                                          @RequestParam Long itemId,
                                          @RequestParam Integer quantity) {
        boolean enough = deliveryService.checkStock(warehouseId, itemId, quantity);
        return Map.of("enough", enough);
    }

    // ==================== 配送员：我的配送单 ====================

    /** 配送员查询自己的配送单列表 */
    @GetMapping("/my/list")
    public List<Map<String, Object>> myList(Authentication auth,
                                            @RequestParam(required = false) String status,
                                            @RequestParam(defaultValue = "50") int limit) {
        Long courierId = getCourierId(auth);
        return deliveryService.listByCourier(courierId, status, limit);
    }

    /** 配送员查询自己的配送单详情 */
    @GetMapping("/my/{deliveryNo}")
    public Map<String, Object> myDetail(Authentication auth, @PathVariable String deliveryNo) {
        Long courierId = getCourierId(auth);
        Map<String, Object> detail = deliveryService.getDetail(deliveryNo);
        if (detail == null) throw new IllegalArgumentException("配送单不存在：" + deliveryNo);
        // 权限校验：只能看自己的
        Long detailCourierId = detail.get("courier_id") == null ? null
                : ((Number) detail.get("courier_id")).longValue();
        if (detailCourierId != null && !detailCourierId.equals(courierId) && !isAdmin(auth)) {
            throw new IllegalStateException("无权查看他人的配送单");
        }
        return detail;
    }

    /** 配送员确认送达 */
    @PostMapping("/my/{deliveryNo}/delivered")
    public Map<String, Object> confirmDelivered(Authentication auth, @PathVariable String deliveryNo) {
        String username = auth.getName();
        deliveryService.confirmDelivered(deliveryNo, username);
        return Map.of("ok", true);
    }

    // ==================== 私有辅助 ====================

    private Long getCourierId(Authentication auth) {
        String username = auth.getName();
        // 从 sys_user 表查 courier_id
        // 这里简化：通过 JdbcTemplate 查，实际可注入 DeliveryService 或单独 UserService
        // 为保持 Controller 简洁，直接调用 DeliveryService 的辅助方法
        return deliveryService.getCurrentUserId(username);
    }

    private boolean isAdmin(Authentication auth) {
        return auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(a -> a.equals("ROLE_ADMIN"));
    }

    // ==================== 表单 ====================

    public record CreateForm(Long orderId, Long courierId, Long warehouseId,
                             String address, String destination, String customerPhone,
                             BigDecimal fromLng, BigDecimal fromLat,
                             BigDecimal toLng, BigDecimal toLat,
                             LocalDateTime deadline, String remark) {}
}
