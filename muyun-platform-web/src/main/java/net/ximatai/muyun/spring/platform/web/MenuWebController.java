package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.web.WebListResponse;
import net.ximatai.muyun.spring.web.WebTreeNode;
import net.ximatai.muyun.spring.platform.menu.Menu;
import net.ximatai.muyun.spring.platform.menu.MenuService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/platform.menu")
public class MenuWebController {
    private final MenuService menuService;

    public MenuWebController(MenuService menuService) {
        this.menuService = menuService;
    }

    @GetMapping("/mine")
    public WebListResponse<WebTreeNode<MenuNavigationView>> mine() {
        return new WebListResponse<>(menuService.currentUserVisibleRootMenus().stream()
                .map(this::node)
                .toList());
    }

    private WebTreeNode<MenuNavigationView> node(Menu menu) {
        List<WebTreeNode<MenuNavigationView>> children = menuService.visibleChildren(menu.getSchemeId(), menu.getId())
                .stream()
                .map(this::node)
                .toList();
        return new WebTreeNode<>(MenuNavigationView.from(menu, menuService.navigationEntryType(menu)), children);
    }
}
