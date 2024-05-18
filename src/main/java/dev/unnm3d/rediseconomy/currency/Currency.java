package dev.unnm3d.rediseconomy.currency;

import dev.unnm3d.rediseconomy.RedisEconomyPlugin;
import dev.unnm3d.rediseconomy.config.Settings;
import dev.unnm3d.rediseconomy.transaction.AccountID;
import dev.unnm3d.rediseconomy.transaction.Transaction;
import io.lettuce.core.ScoredValue;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import lombok.AllArgsConstructor;
import lombok.Getter;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.*;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static dev.unnm3d.rediseconomy.redis.RedisKeys.*;

@AllArgsConstructor
public class Currency implements Economy {
    protected final CurrenciesManager currenciesManager;

    @Getter
    protected final String currencyName;
    private final ConcurrentHashMap<UUID, Double> accounts;

    private boolean enabled;
    @Getter
    private String currencySingular;
    @Getter
    private String currencyPlural;
    @Getter
    private final DecimalFormat decimalFormat;
    @Getter
    private final double startingBalance;
    @Getter
    private final double maxBalance;
    private boolean saveTransactions;
    @Getter
    private double transactionTax;
    @Getter
    private final boolean taxOnlyPay;
    protected final ExecutorService updateExecutor;


    /**
     * Creates a new currency.
     * Currency implements Economy from Vault, so it's the same as using any other Vault Economy plugin
     *
     * @param currenciesManager The CurrenciesManager instance
     * @param currencySettings  The currency settings
     */
    public Currency(CurrenciesManager currenciesManager, Settings.CurrencySettings currencySettings) {
        this.currenciesManager = currenciesManager;
        this.enabled = true;
        this.updateExecutor = Executors.newSingleThreadExecutor();
        this.currencyName = currencySettings.currencyName();
        this.currencySingular = currencySettings.currencySingle();
        this.currencyPlural = currencySettings.currencyPlural();
        this.startingBalance = currencySettings.startingBalance();
        this.maxBalance = currencySettings.maxBalance() == 0.0d ? Double.POSITIVE_INFINITY : currencySettings.maxBalance();
        this.saveTransactions = currencySettings.saveTransactions();
        this.transactionTax = currencySettings.payTax();
        this.taxOnlyPay = currencySettings.taxOnlyPay();
        this.accounts = new ConcurrentHashMap<>();
        this.decimalFormat = new DecimalFormat(
                currencySettings.decimalFormat() != null ? currencySettings.decimalFormat() : "#.##",
                new DecimalFormatSymbols(Locale.forLanguageTag(currencySettings.languageTag() != null ? currencySettings.languageTag() : "en-US"))
        );
        getOrderedAccounts(-1).thenApply(result -> {
                    result.forEach(t ->
                            accounts.put(UUID.fromString(t.getValue()), t.getScore()));
                    if (RedisEconomyPlugin.getInstance().settings().debug && !accounts.isEmpty()) {
                        Bukkit.getLogger().info("start1 Loaded " + accounts.size() + " accounts for currency " + currencyName);
                    }
                    return result;
                }
        ).toCompletableFuture().join(); //Wait to avoid API calls before accounts are loaded
        registerUpdateListener();
    }


    private void registerUpdateListener() {
        StatefulRedisPubSubConnection<String, String> connection = currenciesManager.getRedisManager().getPubSubConnection();
        connection.addListener(new RedisCurrencyListener() {
            @Override
            public void message(String channel, String message) {
                String[] split = message.split(";;");
                if (split.length != 4) {
                    Bukkit.getLogger().severe("Invalid message received from RedisEco channel, consider updating RedisEconomy");
                    return;
                }
                if (split[0].equals(RedisEconomyPlugin.getInstanceUUID().toString())) return;
                UUID uuid = UUID.fromString(split[1]);
                String playerName = split[2];
                double balance = Double.parseDouble(split[3]);
                updateAccountLocal(uuid, playerName, balance);
                if (RedisEconomyPlugin.getInstance().settings().debug) {
                    Bukkit.getLogger().info("01b Received balance update " + playerName + " to " + balance);
                }
            }
        });
        connection.async().subscribe(UPDATE_PLAYER_CHANNEL_PREFIX + currencyName);
        if (RedisEconomyPlugin.getInstance().settings().debug) {
            Bukkit.getLogger().info("start1b Listening to RedisEco channel " + UPDATE_PLAYER_CHANNEL_PREFIX + currencyName);
        }
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    public boolean shouldSaveTransactions() {
        return saveTransactions;
    }

    public void setShouldSaveTransactions(boolean saveTransactions) {
        this.saveTransactions = saveTransactions;
    }

    @Override
    public String getName() {
        return "RedisEconomy";
    }

    @Override
    public boolean hasBankSupport() {
        return false;
    }

    @Override
    public int fractionalDigits() {
        return 0;
    }

    @Override
    public String format(double amount) {
        return decimalFormat.format(amount) + (amount == 1 ? currencySingular : currencyPlural);
    }

    @Override
    public String currencyNamePlural() {
        return currencyPlural;
    }

    @Override
    public String currencyNameSingular() {
        return currencySingular;
    }

    @Override
    public boolean hasAccount(@NotNull String playerName) {
        UUID playerUniqueId = currenciesManager.getUUIDFromUsernameCache(playerName);
        if (playerUniqueId == null) return false;
        return hasAccount(playerUniqueId);
    }

    @Override
    public boolean hasAccount(@NotNull OfflinePlayer player) {
        return hasAccount(player.getUniqueId());
    }

    @Override
    public boolean hasAccount(@NotNull String playerName, String worldName) {
        return hasAccount(playerName);
    }

    @Override
    public boolean hasAccount(@NotNull OfflinePlayer player, String worldName) {
        return hasAccount(player);
    }

    public boolean hasAccount(@NotNull UUID playerUUID) {
        return accounts.containsKey(playerUUID);
    }

    public double getBalance(@NotNull UUID playerUUID) {
        return accounts.getOrDefault(playerUUID, 0.0D);
    }

    @Override
    public double getBalance(@NotNull String playerName) {
        UUID playerUniqueId = currenciesManager.getUUIDFromUsernameCache(playerName);
        if (playerUniqueId == null) return 0.0D;
        return getBalance(playerUniqueId);
    }

    @Override
    public double getBalance(@NotNull OfflinePlayer player) {
        return getBalance(player.getUniqueId());
    }

    @Override
    public double getBalance(@NotNull String playerName, String world) {
        return getBalance(playerName);
    }

    @Override
    public double getBalance(@NotNull OfflinePlayer player, String world) {
        return getBalance(player);
    }

    @Override
    public boolean has(@NotNull String playerName, double amount) {
        return getBalance(playerName) >= amount;
    }

    @Override
    public boolean has(@NotNull OfflinePlayer player, double amount) {
        return has(player.getUniqueId(), amount);
    }

    public boolean has(@NotNull UUID playerUUID, double amount) {
        return getBalance(playerUUID) >= amount;
    }

    @Override
    public boolean has(@NotNull String playerName, String worldName, double amount) {
        return has(playerName, amount);
    }

    @Override
    public boolean has(@NotNull OfflinePlayer player, String worldName, double amount) {
        return has(player, amount);
    }

    @Override
    public EconomyResponse withdrawPlayer(@NotNull String playerName, double amount) {
        return withdrawPlayer(playerName, amount, "Withdraw");
    }

    @Override
    public EconomyResponse withdrawPlayer(@NotNull OfflinePlayer player, double amount) {
        return withdrawPlayer(player.getUniqueId(), player.getName(), amount, "Withdraw");
    }

    @Override
    public EconomyResponse withdrawPlayer(@NotNull String playerName, String worldName, double amount) {
        return withdrawPlayer(playerName, amount);
    }

    @Override
    public EconomyResponse withdrawPlayer(@NotNull OfflinePlayer player, String worldName, double amount) {
        return withdrawPlayer(player, amount);
    }

    @Override
    public EconomyResponse depositPlayer(@NotNull String playerName, double amount) {
        return depositPlayer(playerName, amount, "Deposit");
    }

    @Override
    public EconomyResponse depositPlayer(@NotNull OfflinePlayer player, double amount) {
        return depositPlayer(player.getUniqueId(), player.getName(), amount, "Deposit");
    }

    @Override
    public EconomyResponse depositPlayer(@NotNull String playerName, String worldName, double amount) {
        return depositPlayer(playerName, amount);
    }

    @Override
    public EconomyResponse depositPlayer(@NotNull OfflinePlayer player, String worldName, double amount) {
        return depositPlayer(player, amount);
    }

    @Override
    public EconomyResponse createBank(String name, String player) {
        return new EconomyResponse(0, 0, EconomyResponse.ResponseType.NOT_IMPLEMENTED, "Not implemented");
    }

    @Override
    public EconomyResponse createBank(String name, OfflinePlayer player) {
        return new EconomyResponse(0, 0, EconomyResponse.ResponseType.NOT_IMPLEMENTED, "Not implemented");
    }

    @Override
    public EconomyResponse deleteBank(String name) {
        return new EconomyResponse(0, 0, EconomyResponse.ResponseType.NOT_IMPLEMENTED, "Not implemented");
    }

    @Override
    public EconomyResponse bankBalance(String name) {
        return new EconomyResponse(0, 0, EconomyResponse.ResponseType.NOT_IMPLEMENTED, "Not implemented");
    }

    @Override
    public EconomyResponse bankHas(String name, double amount) {
        return new EconomyResponse(0, 0, EconomyResponse.ResponseType.NOT_IMPLEMENTED, "Not implemented");
    }

    @Override
    public EconomyResponse bankWithdraw(String name, double amount) {
        return new EconomyResponse(0, 0, EconomyResponse.ResponseType.NOT_IMPLEMENTED, "Not implemented");
    }

    @Override
    public EconomyResponse bankDeposit(String name, double amount) {
        return new EconomyResponse(0, 0, EconomyResponse.ResponseType.NOT_IMPLEMENTED, "Not implemented");
    }

    @Override
    public EconomyResponse isBankOwner(String name, String playerName) {
        return new EconomyResponse(0, 0, EconomyResponse.ResponseType.NOT_IMPLEMENTED, "Not implemented");
    }

    @Override
    public EconomyResponse isBankOwner(String name, OfflinePlayer player) {
        return new EconomyResponse(0, 0, EconomyResponse.ResponseType.NOT_IMPLEMENTED, "Not implemented");
    }

    @Override
    public EconomyResponse isBankMember(String name, String playerName) {
        return new EconomyResponse(0, 0, EconomyResponse.ResponseType.NOT_IMPLEMENTED, "Not implemented");
    }

    @Override
    public EconomyResponse isBankMember(String name, OfflinePlayer player) {
        return new EconomyResponse(0, 0, EconomyResponse.ResponseType.NOT_IMPLEMENTED, "Not implemented");
    }

    @Override
    public List<String> getBanks() {
        return List.of();
    }

    @Override
    public boolean createPlayerAccount(@NotNull String playerName) {
        UUID playerUUID = currenciesManager.getUUIDFromUsernameCache(playerName);
        if (playerUUID == null)
            return false;
        return createPlayerAccount(playerUUID, playerName);
    }

    @Override
    public boolean createPlayerAccount(@NotNull OfflinePlayer player) {
        return createPlayerAccount(player.getUniqueId(), player.getName());
    }

    public boolean createPlayerAccount(@NotNull UUID playerUUID, @Nullable String playerName) {
        if (hasAccount(playerUUID))
            return false;
        updateAccount(playerUUID, playerName, startingBalance);
        currenciesManager.getExchange().saveTransaction(new AccountID(playerUUID), new AccountID(), startingBalance, this, "Account creation");
        return true;
    }

    @Override
    public boolean createPlayerAccount(@NotNull String playerName, @Nullable String worldName) {
        return createPlayerAccount(playerName);
    }

    @Override
    public boolean createPlayerAccount(@NotNull OfflinePlayer player, @Nullable String worldName) {
        return createPlayerAccount(player);
    }

    public EconomyResponse withdrawPlayer(@NotNull String playerName, double amount, @Nullable String reason) {
        UUID playerUniqueId = currenciesManager.getUUIDFromUsernameCache(playerName);
        if (playerUniqueId == null)
            return new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, "Player not found");
        return withdrawPlayer(playerUniqueId, playerName, amount, reason);
    }


    public EconomyResponse withdrawPlayer(@NotNull UUID playerUUID, @Nullable String playerName, double amount, @Nullable String reason) {
        if (!hasAccount(playerUUID))
            return new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, "Account not found");
        double amountToWithdraw = amount + (taxOnlyPay ? 0d : amount * transactionTax);
        if (amountToWithdraw == Double.POSITIVE_INFINITY || amountToWithdraw == Double.NEGATIVE_INFINITY || Double.isNaN(amountToWithdraw))
            return new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, "Invalid decimal amount format");

        if (!has(playerUUID, amountToWithdraw))
            return new EconomyResponse(amountToWithdraw, getBalance(playerUUID), EconomyResponse.ResponseType.FAILURE, "Insufficient funds");

        updateAccount(playerUUID, playerName, getBalance(playerUUID) - amountToWithdraw);
        currenciesManager.getExchange().saveTransaction(new AccountID(playerUUID), new AccountID(), -amountToWithdraw, this, reason == null ? "Withdraw" : reason);
        return new EconomyResponse(amount, getBalance(playerUUID), EconomyResponse.ResponseType.SUCCESS, null);
    }

    @SuppressWarnings("unused")
    public EconomyResponse payPlayer(@NotNull UUID sender, @NotNull UUID receiver, double amount, @Nullable String reason) {
        String senderName = currenciesManager.getUsernameFromUUIDCache(sender);
        if (senderName == null || !hasAccount(sender))
            return new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, "Account not found");
        //same for receiver
        String receiverName = currenciesManager.getUsernameFromUUIDCache(receiver);
        if (receiverName == null || !hasAccount(receiver))
            return new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, "Account not found");
        double amountToWithdraw = amount + (amount * transactionTax);

        if (amountToWithdraw == Double.POSITIVE_INFINITY || amountToWithdraw == Double.NEGATIVE_INFINITY || Double.isNaN(amountToWithdraw))
            return new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, "Invalid decimal amount format");

        if (!has(sender, amountToWithdraw))
            return new EconomyResponse(0, getBalance(sender), EconomyResponse.ResponseType.FAILURE, "Insufficient funds");

        if (getBalance(receiver) + amount > maxBalance)
            return new EconomyResponse(0, getBalance(receiver), EconomyResponse.ResponseType.FAILURE, "The receiver has reached the maximum balance");

        updateAccount(sender, senderName, getBalance(sender) - amountToWithdraw);
        currenciesManager.getExchange().saveTransaction(new AccountID(sender), new AccountID(receiver), -amountToWithdraw, this, reason == null ? "Payment" : reason);
        updateAccount(sender, receiverName, getBalance(receiver) + amount);
        currenciesManager.getExchange().saveTransaction(new AccountID(receiver), new AccountID(sender), amount, this, reason == null ? "Payment" : reason);

        return new EconomyResponse(amount, getBalance(sender), EconomyResponse.ResponseType.SUCCESS, null);
    }

    public EconomyResponse payPlayer(@NotNull String senderName, @NotNull String receiverName, double amount) {
        if (!hasAccount(senderName))
            return new EconomyResponse(amount, getBalance(senderName), EconomyResponse.ResponseType.FAILURE, "Account not found");
        if (!hasAccount(receiverName))
            return new EconomyResponse(amount, getBalance(receiverName), EconomyResponse.ResponseType.FAILURE, "Account not found");

        final UUID sender = currenciesManager.getUUIDFromUsernameCache(senderName);
        final UUID receiver = currenciesManager.getUUIDFromUsernameCache(receiverName);

        //Calculate the amount to withdraw with the transaction tax
        double amountToWithdraw = amount + (amount * transactionTax);
        if (sender == null || receiver == null)
            return new EconomyResponse(amount, getBalance(senderName), EconomyResponse.ResponseType.FAILURE, "Account not found");

        if (amountToWithdraw == Double.POSITIVE_INFINITY || amountToWithdraw == Double.NEGATIVE_INFINITY || Double.isNaN(amountToWithdraw))
            return new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, "Invalid decimal amount format");

        if (!has(senderName, amountToWithdraw))
            return new EconomyResponse(0, getBalance(sender), EconomyResponse.ResponseType.FAILURE, "Insufficient funds");

        if (getBalance(receiver) + amount > maxBalance)
            return new EconomyResponse(0, getBalance(receiver), EconomyResponse.ResponseType.FAILURE, "The receiver has reached the maximum balance");

        updateAccount(sender, senderName, getBalance(sender) - amountToWithdraw);
        updateAccount(receiver, receiverName, getBalance(receiver) + amount);

        return new EconomyResponse(amount, getBalance(sender), EconomyResponse.ResponseType.SUCCESS, null);
    }

    /**
     * Set the balance of a player
     *
     * @param player The player to set the balance of
     * @param amount The amount to set the balance to
     * @return The result of the operation
     */
    @SuppressWarnings("unused")
    public EconomyResponse setPlayerBalance(@NotNull OfflinePlayer player, double amount) {
        return setPlayerBalance(player.getUniqueId(), player.getName(), amount);
    }

    /**
     * Set the balance of a player
     *
     * @param playerUUID The player uuid to set the balance of
     * @param amount     The amount to set the balance to
     * @return The result of the operation
     */
    public EconomyResponse setPlayerBalance(@NotNull UUID playerUUID, @Nullable String playerName, double amount) {
        if (amount == Double.POSITIVE_INFINITY || amount == Double.NEGATIVE_INFINITY || Double.isNaN(amount))
            return new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, "Invalid decimal amount format");
        updateAccount(playerUUID, playerName, amount);
        currenciesManager.getExchange().saveTransaction(new AccountID(playerUUID), new AccountID(), -getBalance(playerUUID), this, "Reset balance");
        currenciesManager.getExchange().saveTransaction(new AccountID(playerUUID), new AccountID(), amount, this, "Set balance");
        return new EconomyResponse(amount, getBalance(playerUUID), EconomyResponse.ResponseType.SUCCESS, null);
    }

    /**
     * Revert a transaction
     *
     * @param transactionId The transaction id
     * @param transaction   The transaction to revert
     * @return The transaction id that reverted the initial transaction
     */
    public CompletionStage<Integer> revertTransaction(int transactionId, @NotNull Transaction transaction) {
        String ownerName = transaction.getAccountIdentifier().isPlayer() ?//If the sender is a player
                currenciesManager.getUsernameFromUUIDCache(transaction.getAccountIdentifier().getUUID()) : //Get the username from the cache (with server uuid translation)
                transaction.getAccountIdentifier().toString(); //Else, it's a bank, so we get the bank id
        if (transaction.getAccountIdentifier().isPlayer()) {
            updateAccount(transaction.getAccountIdentifier().getUUID(), ownerName, getBalance(transaction.getAccountIdentifier().getUUID()) - transaction.getAmount());
        }
        if (RedisEconomyPlugin.getInstance().settings().debug) {
            Bukkit.getLogger().info("revert01a reverted on account " + transaction.getAccountIdentifier() + " amount " + transaction.getAmount());
        }
        return currenciesManager.getExchange().saveTransaction(transaction.getAccountIdentifier(), transaction.getActor(), -transaction.getAmount(), this, "Revert #" + transactionId + ": " + transaction.getReason());
    }

    /**
     * Set the balance of a player
     *
     * @param playerName The player to set the balance of
     * @param amount     The amount to set the balance to
     * @return The result of the operation
     */
    public EconomyResponse setPlayerBalance(@NotNull String playerName, double amount) {
        UUID playerUUID = currenciesManager.getUUIDFromUsernameCache(playerName);
        if (playerUUID == null)
            return new EconomyResponse(amount, 0, EconomyResponse.ResponseType.FAILURE, "Player not found");
        return setPlayerBalance(playerUUID, playerName, amount);
    }

    public EconomyResponse depositPlayer(@NotNull String playerName, double amount, @Nullable String reason) {
        UUID playerUUID = currenciesManager.getUUIDFromUsernameCache(playerName);
        if (playerUUID == null)
            return new EconomyResponse(amount, 0, EconomyResponse.ResponseType.FAILURE, "Player not found");
        return depositPlayer(playerUUID, playerName, amount, reason);
    }

    public EconomyResponse depositPlayer(@NotNull UUID playerUUID, @Nullable String playerName, double amount, String reason) {
        if (!hasAccount(playerUUID))
            return new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, "Account not found");

        if (amount == Double.POSITIVE_INFINITY || amount == Double.NEGATIVE_INFINITY || Double.isNaN(amount))
            return new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, "Invalid decimal amount format");

        if (getBalance(playerUUID) + amount > maxBalance)
            return new EconomyResponse(0, getBalance(playerUUID), EconomyResponse.ResponseType.FAILURE, "The player has reached the maximum balance");

        updateAccount(playerUUID, playerName, getBalance(playerUUID) + amount);
        currenciesManager.getExchange().saveTransaction(new AccountID(playerUUID), new AccountID(), amount, this, reason == null ? "Deposit" : reason);
        return new EconomyResponse(amount, getBalance(playerUUID), EconomyResponse.ResponseType.SUCCESS, null);
    }

    public void updateAccountLocal(@NotNull UUID uuid, @Nullable String playerName, double balance) {
        if (playerName != null)
            currenciesManager.updateNameUniqueId(playerName, uuid);
        accounts.put(uuid, balance);
    }

    protected void updateAccount(@NotNull UUID uuid, @Nullable String playerName, double balance) {
        updateAccountCloudCache(uuid, playerName, balance, 0);
        updateAccountLocal(uuid, playerName, balance);
    }

    private synchronized void updateAccountCloudCache(@NotNull UUID uuid, @Nullable String playerName, double balance, int tries) {
        updateExecutor.submit(() -> {
            currenciesManager.getRedisManager().executeTransaction(reactiveCommands -> {
                reactiveCommands.zadd(BALANCE_PREFIX + currencyName, balance, uuid.toString());
                if (playerName != null)
                    reactiveCommands.hset(NAME_UUID.toString(), playerName, uuid.toString());
                reactiveCommands.publish(UPDATE_PLAYER_CHANNEL_PREFIX + currencyName,
                        RedisEconomyPlugin.getInstanceUUID().toString() + ";;" + uuid + ";;" + playerName + ";;" + balance);
            }).ifPresentOrElse(result -> {

                if (RedisEconomyPlugin.getInstance().settings().debug) {
                    Bukkit.getLogger().info("01 Sent update account " + playerName + " to " + balance + " currency " + currencyName);
                }
            }, () -> {
                if (tries < 3) {
                    Bukkit.getLogger().severe("Player accounts are desynchronized");
                    updateAccountCloudCache(uuid, playerName, balance, tries + 1);
                } else {
                    Bukkit.getLogger().severe("Failed to update account " + playerName + " after 3 tries");
                    throw new RuntimeException("Player accounts are desynchronized");
                }
            });
        });
    }

    /**
     * Update the balances of all players and their nameuuids
     * Do not use this method unless you know what you are doing
     *
     * @param balances  The balances to update
     * @param nameUUIDs The name-uuids to update
     */
    @SuppressWarnings("unchecked")
    public void updateBulkAccountsCloudCache(@NotNull List<ScoredValue<String>> balances, @NotNull Map<String, String> nameUUIDs) {
        currenciesManager.getRedisManager().executeTransaction(commands -> {
            ScoredValue<String>[] balancesArray = new ScoredValue[balances.size()];
            balances.toArray(balancesArray);

            commands.zadd(BALANCE_PREFIX + currencyName, balancesArray);
            commands.hset(NAME_UUID.toString(), nameUUIDs);
        }).ifPresent(result -> {
            Bukkit.getLogger().info("migration01 updated balances into " + BALANCE_PREFIX + currencyName + " accounts. result " + result.get(0));
            Bukkit.getLogger().info("migration02 updated nameuuids into " + NAME_UUID + " accounts. result " + result.get(1));

        });
    }

    /**
     * Get ordered accounts from Redis
     * Redis uses ordered sets as data structures
     * This method has a time complexity of O(log(N)+M) with N being the number of elements in the sorted set and M the number of elements returned.
     *
     * @param limit The number of accounts to return from the top 1
     * @return A list of accounts ordered by balance in Tuples of UUID and balance (UUID is stringified)
     */
    public CompletionStage<List<ScoredValue<String>>> getOrderedAccounts(int limit) {
        return currenciesManager.getRedisManager().getConnectionAsync(accounts ->
                accounts.zrevrangeWithScores(BALANCE_PREFIX + currencyName, 0, limit));

    }

    /**
     * Get single ordered account from Redis
     *
     * @param uuid The UUID of the account
     * @return The balance associated with the UUID on Redis
     */
    public CompletionStage<Double> getAccountRedis(UUID uuid) {
        return currenciesManager.getRedisManager().getConnectionAsync(connection -> connection.zscore(BALANCE_PREFIX + currencyName, uuid.toString()));
    }

    /**
     * Get all accounts in cache
     *
     * @return Unmodifiable map of accounts
     */
    @SuppressWarnings("unused")
    public final Map<UUID, Double> getAccounts() {
        return Collections.unmodifiableMap(accounts);
    }


}
