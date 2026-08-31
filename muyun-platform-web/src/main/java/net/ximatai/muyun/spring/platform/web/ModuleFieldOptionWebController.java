package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.common.option.OptionItem;
import net.ximatai.muyun.spring.common.platform.ActionEndpoint;
import net.ximatai.muyun.spring.common.platform.PlatformAction;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/platform.module/{moduleAlias}/fields/{fieldName}/options")
public class ModuleFieldOptionWebController {
    private final ModuleFieldOptionService optionService;

    public ModuleFieldOptionWebController(ModuleFieldOptionService optionService) {
        this.optionService = optionService;
    }

    @GetMapping
    @ActionEndpoint(PlatformAction.MENU)
    public List<OptionItem> options(@PathVariable String moduleAlias,
                                    @PathVariable String fieldName,
                                    @RequestParam(required = false) String entityAlias,
                                    @RequestParam(defaultValue = "true") boolean enabledOnly,
                                    @RequestParam(required = false) String parentCode) {
        return optionService.options(moduleAlias, entityAlias, fieldName, enabledOnly, parentCode);
    }
}
