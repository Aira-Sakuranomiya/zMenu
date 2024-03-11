package fr.maxlego08.menu.website;

import com.google.gson.JsonObject;
import fr.maxlego08.menu.MenuPlugin;
import fr.maxlego08.menu.api.ButtonManager;
import fr.maxlego08.menu.api.InventoryManager;
import fr.maxlego08.menu.api.website.WebsiteManager;
import fr.maxlego08.menu.button.loader.NoneLoader;
import fr.maxlego08.menu.exceptions.InventoryException;
import fr.maxlego08.menu.placeholder.LocalPlaceholder;
import fr.maxlego08.menu.website.buttons.ButtonBuilderRefresh;
import fr.maxlego08.menu.website.buttons.ButtonFolderBack;
import fr.maxlego08.menu.website.buttons.ButtonFolderNext;
import fr.maxlego08.menu.website.buttons.ButtonFolderPrevious;
import fr.maxlego08.menu.website.buttons.ButtonFolders;
import fr.maxlego08.menu.website.buttons.ButtonInventories;
import fr.maxlego08.menu.website.buttons.ButtonMarketplace;
import fr.maxlego08.menu.website.request.HttpRequest;
import fr.maxlego08.menu.zcore.enums.Message;
import fr.maxlego08.menu.zcore.utils.ZUtils;
import fr.maxlego08.menu.zcore.utils.nms.NMSUtils;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

public class ZWebsiteManager extends ZUtils implements WebsiteManager {

    // private final String API_URL = "http://mib.test/api/v1/";
    private final String API_URL = "https://minecraft-inventory-builder.com/api/v1/";
    private final MenuPlugin plugin;
    private final List<Folder> folders = new ArrayList<>();
    private boolean isLogin = false;
    private boolean isDownloadResource = false;
    private long lastResourceUpdate = 0;
    private List<Resource> resources = new ArrayList<>();
    private int folderPage = 1;
    private int inventoryPage = 1;
    private int currentFolderId = -1;
    private int baseFolderId = 1;

    public ZWebsiteManager(MenuPlugin plugin) {
        super();
        this.plugin = plugin;
    }

    @Override
    public void login(CommandSender sender, String token) {

        if (token == null) {
            message(sender, Message.WEBSITE_LOGIN_ERROR_TOKEN);
            return;
        }

        if (Token.token != null) {
            message(sender, Message.WEBSITE_LOGIN_ERROR_ALREADY);
            return;
        }

        String[] parts = token.split("\\|");
        if (parts.length != 2) {
            message(sender, Message.WEBSITE_LOGIN_ERROR_TOKEN);
            return;
        }

        String code = parts[1];
        if (code.length() != 40) {
            message(sender, Message.WEBSITE_LOGIN_ERROR_TOKEN);
            return;
        }

        if (this.isLogin) {
            message(sender, Message.WEBSITE_LOGIN_PROCESS);
            return;
        }

        this.isLogin = true;

        message(sender, Message.WEBSITE_LOGIN_PROCESS);

        JsonObject data = new JsonObject();
        HttpRequest request = new HttpRequest(this.API_URL + "auth/test", data);
        request.setBearer(token);
        request.submit(this.plugin, map -> {
            this.isLogin = false;
            boolean status = map.getOrDefault("status", false);
            if (status) {
                Token.token = token;
                Token.getInstance().save(this.plugin.getPersist());
                message(sender, Message.WEBSITE_LOGIN_SUCCESS);
            } else {
                message(sender, Message.WEBSITE_LOGIN_ERROR_INFO);
            }
        });

    }

    @Override
    public void disconnect(CommandSender sender) {

        if (Token.token == null) {
            message(sender, Message.WEBSITE_DISCONNECT_ERROR);
            return;
        }

        Token.token = null;
        Token.getInstance().save(this.plugin.getPersist());
        message(sender, Message.WEBSITE_DISCONNECT_SUCCESS);

    }

    @Override
    public void openMarketplace(Player player) {

        if (Token.token == null) {
            message(player, Message.WEBSITE_NOT_CONNECT);
            return;
        }


        // Pas besoin de télécharger les resources
        if (this.lastResourceUpdate > System.currentTimeMillis()) {

            openMarketplaceInventory(player);
        } else {

            message(player, Message.WEBSITE_MARKETPLACE_WAIT);

            if (this.isDownloadResource) return;
            this.isDownloadResource = true;

            JsonObject data = new JsonObject();
            HttpRequest request = new HttpRequest(this.API_URL + "resources", data);
            request.setBearer(Token.token);
            request.setMethod("GET");
            request.submit(this.plugin, map -> {

                this.isDownloadResource = false;
                this.lastResourceUpdate = System.currentTimeMillis() + 1000 * 60 * 15;

                List<Map<String, Object>> maps = (List<Map<String, Object>>) map.get("resources");

                this.resources = maps.stream().map(Resource::new).collect(Collectors.toList());

                this.plugin.getScheduler().runTask(player.getLocation(), () -> openMarketplaceInventory(player));
            });
        }
    }

    public List<Resource> getResources() {
        return resources;
    }

    public void registerPlaceholders() {
        LocalPlaceholder localPlaceholder = LocalPlaceholder.getInstance();
        localPlaceholder.register("marketplace_resources", (a, b) -> String.valueOf(this.resources.size()));
    }

    public void openMarketplaceInventory(Player player) {
        this.plugin.getInventoryManager().openInventory(player, this.plugin, "marketplace");
    }

    public void openInventoriesInventory(Player player, int inventorypage, int folderPage, int currentFolderId) {
        this.inventoryPage = inventorypage;
        this.folderPage = folderPage;
        this.currentFolderId = currentFolderId;
        this.plugin.getInventoryManager().openInventory(player, this.plugin, "inventories");
    }

    public void loadButtons(ButtonManager buttonManager) {
        buttonManager.register(new NoneLoader(this.plugin, ButtonMarketplace.class, "zmenu_marketplace_resources"));

        buttonManager.register(new NoneLoader(this.plugin, ButtonFolders.class, "zmenu_builder_folders"));
        buttonManager.register(new NoneLoader(this.plugin, ButtonFolderNext.class, "zmenu_builder_folder_next"));
        buttonManager.register(new NoneLoader(this.plugin, ButtonFolderPrevious.class, "zmenu_builder_folder_previous"));
        buttonManager.register(new NoneLoader(this.plugin, ButtonFolderBack.class, "zmenu_builder_folder_back"));

        buttonManager.register(new NoneLoader(this.plugin, ButtonBuilderRefresh.class, "zmenu_builder_refresh"));
        buttonManager.register(new NoneLoader(this.plugin, ButtonInventories.class, "zmenu_builder_inventories"));
    }

    private void loadFiles() {

        List<String> files = new ArrayList<>();

        // files.add("website/marketplace.yml");
        files.add("website/inventories.yml");

        files.forEach(filePath -> {
            if (!new File(this.plugin.getDataFolder(), filePath).exists()) {

                if (NMSUtils.isNewVersion()) {
                    this.plugin.saveResource(filePath.replace("website/", "website/1_13/"), filePath, false);
                } else {
                    this.plugin.saveResource(filePath, false);
                }
            }
        });
    }

    public void loadInventories(InventoryManager inventoryManager) {

        this.loadFiles();

        try {
            // inventoryManager.loadInventory(this.plugin, "website/marketplace.yml", InventoryMarketplace.class);
            inventoryManager.loadInventory(this.plugin, "website/inventories.yml");
        } catch (InventoryException exception) {
            exception.printStackTrace();
        }
    }

    public void fetchInventories(Player player) {

        if (Token.token == null) {
            message(player, Message.WEBSITE_NOT_CONNECT);
            return;
        }

        if (!this.folders.isEmpty()) {
            openInventoriesInventory(player, 1, 1, this.baseFolderId);
            return;
        }

        message(player, Message.WEBSITE_MARKETPLACE_WAIT);

        JsonObject data = new JsonObject();
        HttpRequest request = new HttpRequest(this.API_URL + "inventories", data);
        request.setBearer(Token.token);
        request.setMethod("GET");
        request.submit(this.plugin, map -> {

            boolean status = map.getOrDefault("status", false);
            if (status) {
                List<Map<String, Object>> folderMaps = (List<Map<String, Object>>) map.get("folders");

                this.folders.clear();
                for (Map<String, Object> folderMap : folderMaps) {
                    Folder folder = Folder.fromMap(folderMap);
                    this.folders.add(folder);
                }

                this.baseFolderId = this.folders.stream().filter(e -> e.getParentId() == -1).map(Folder::getId).findFirst().orElse(-1);

                this.plugin.getScheduler().runTask(player.getLocation(), () -> openInventoriesInventory(player, 1, 1, this.baseFolderId));
            } else {
                message(player, Message.WEBSITE_MARKETPLACE_ERROR);
            }
        });
    }

    public int getFolderPage() {
        return folderPage;
    }

    public void setFolderPage(int folderPage) {
        this.folderPage = folderPage;
    }

    public int getInventoryPage() {
        return inventoryPage;
    }

    public void setInventoryPage(int inventoryPage) {
        this.inventoryPage = inventoryPage;
    }

    public Optional<Folder> getCurrentFolder() {
        return this.folders.stream().filter(e -> e.getId() == currentFolderId).findFirst();
    }

    public Optional<Folder> getFolder(int id) {
        return this.folders.stream().filter(e -> e.getId() == id).findFirst();
    }

    public List<Folder> getFolders(Folder folder) {
        return this.folders.stream().filter(e -> e.getParentId() == folder.getId()).collect(Collectors.toList());
    }

    public void loadPlaceholders() {
        LocalPlaceholder placeholder = LocalPlaceholder.getInstance();
        placeholder.register("folder_name", (player, args) -> {
            Optional<Folder> optional = getCurrentFolder();
            return optional.isPresent() ? optional.get().getName() : "Not found";
        });
    }

    private String getFolderPath(Folder folder, String path) {
        if (folder.getParentId() == -1) {
            return path;
        }
        Optional<Folder> optional = getFolder(folder.getParentId());
        if (optional.isPresent()) {
            Folder parrentFolder = optional.get();
            return getFolderPath(parrentFolder, folder.getName() + "/" + path);
        }
        return folder.getName() + "/" + path;
    }

    private File getFolderPath(Inventory inventory) {
        Optional<Folder> optional = getFolder(inventory.getFolderId());
        return optional.map(folder -> new File(this.plugin.getDataFolder(), "inventories/" + getFolderPath(folder, ""))).orElseGet(() -> new File(this.plugin.getDataFolder(), "inventories"));
    }

    public void downloadInventory(Player player, Inventory inventory, boolean forceDownload) {

        File folder = getFolderPath(inventory);
        File file = new File(folder, inventory.getFileName() + ".yml");

        if (file.exists() && !forceDownload) {
            message(player, Message.WEBSITE_INVENTORY_EXIST);
            return;
        }

        player.closeInventory();
        message(player, Message.WEBSITE_INVENTORY_WAIT, "%name%", inventory.getFileName());

        HttpRequest request = new HttpRequest(this.API_URL + String.format("inventory/%s/download", inventory.getId()), new JsonObject());
        request.setBearer(Token.token);
        request.setMethod("GET");

        folder.mkdirs();

        request.submitForFileDownload(this.plugin, file, isSuccess -> message(player, isSuccess ? Message.WEBSITE_INVENTORY_SUCCESS : Message.WEBSITE_INVENTORY_ERROR, "%name%", inventory.getFileName()));
    }

    public void refreshInventories(Player player) {
        this.folders.clear();
        this.fetchInventories(player);
    }
}
