package com.hr.agent.controller;

import com.hr.agent.service.ExternalToolService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端通用配置接口（跨业务域共享）。
 * 各业务域的 CRUD 已按域拆分到 controller/manage/ 下：
 * HrManageController（员工/职位）、WmsManageController（仓库/库存）、OrderManageController（订单/配送/采购/退货）。
 */
@RestController
@RequestMapping("/api/manage")
public class ManageController {

    private final ExternalToolService externalToolService;

    public ManageController(ExternalToolService externalToolService) {
        this.externalToolService = externalToolService;
    }

    /** 高德前端 JS API 配置（key + jscode），前端加载交互式地图用；未配置时两字段为 null */
    @GetMapping("/amapJsKey")
    public ExternalToolService.AmapJsConfig amapJsKey() {
        return externalToolService.amapJsConfig();
    }
}
