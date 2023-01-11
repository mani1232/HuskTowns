package net.william278.husktowns.migrator;

import net.william278.husktowns.HuskTowns;
import net.william278.husktowns.audit.Log;
import net.william278.husktowns.claim.*;
import net.william278.husktowns.database.Database;
import net.william278.husktowns.town.Spawn;
import net.william278.husktowns.town.Town;
import net.william278.husktowns.user.User;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.math.BigDecimal;
import java.sql.*;
import java.util.*;
import java.util.logging.Level;

/**
 * Migrator for HuskTowns v1.x to v2.x data
 */
public class LegacyMigrator extends Migrator {
    public LegacyMigrator(@NotNull HuskTowns plugin) {
        super(plugin, "Legacy");
        Arrays.stream(Parameters.values()).forEach(flag -> setParameter(flag.name().toLowerCase(), flag.getDefault()));
    }

    @Override
    protected void onStart() {
        // Convert towns
        plugin.getTowns().clear();
        getConvertedTowns().forEach(town -> {
            try {
                plugin.getDatabase().createTown(town.getName(), User.of(town.getMayor(), "(Migrated)"));
                plugin.getDatabase().updateTown(town);
                plugin.getTowns().add(town);
            } catch (IllegalStateException e) {
                plugin.log(Level.WARNING, "Skipped migrating " + town.getName() + ": " + e.getMessage());
            }
        });

        // Convert claims into claim worlds
        plugin.getClaimWorlds().clear();
        getConvertedClaimWorlds().forEach((serverWorld, claimWorld) -> plugin.getDatabase().updateClaimWorld(claimWorld));
        plugin.pruneClaimWorlds();
    }

    @NotNull
    protected List<Town> getConvertedTowns() {
        final List<Town> towns = new ArrayList<>();
        try (Connection connection = getConnection()) {
            try (PreparedStatement statement = connection.prepareStatement(
                    formatStatement("SELECT * FROM %towns%"))) {
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        final BigDecimal balance = BigDecimal.valueOf(resultSet.getDouble("money"));
                        final String name = resultSet.getString("name");
                        final int level = plugin.getLevels().getHighestLevelFor(balance);
                        towns.add(Town.of(resultSet.getInt("id"),
                                name,
                                resultSet.getString("bio"),
                                resultSet.getString("greeting_message"),
                                resultSet.getString("farewell_message"),
                                new HashMap<>(),
                                plugin.getRulePresets().getDefaultClaimRules(),
                                0,
                                balance.subtract(plugin.getLevels().getTotalCostFor(level)),
                                level,
                                null,
                                Log.empty(),
                                Town.getRandomColor(name),
                                0,
                                0));
                    }
                }
            }

            // Set the members for each town
            try (PreparedStatement statement = connection.prepareStatement(
                    formatStatement("SELECT * FROM %players% WHERE `town_id` IS NOT NULL"))) {
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        final int townId = resultSet.getInt("town_id");
                        final int roleWeight = resultSet.getInt("town_role");
                        final Town town = towns.stream().filter(t -> t.getId() == townId).findFirst().orElseThrow();
                        town.addMember(UUID.fromString(resultSet.getString("uuid")),
                                plugin.getRoles().fromWeight(roleWeight)
                                        .or(() -> {
                                            plugin.log(Level.WARNING, "No role found for weight: " + roleWeight + " - expect errors! " +
                                                                      "Have you updated your roles.yml to match your existing setup? If not, stop the server, " +
                                                                      "reset your database and start migration again.");
                                            return Optional.of(plugin.getRoles().getDefaultRole());
                                        }).orElseThrow());
                    }
                }
            }

            // Select the number of claims for each town
            try (PreparedStatement statement = connection.prepareStatement(
                    formatStatement("SELECT `town_id`, COUNT(*) AS `claims` FROM %claims% GROUP BY `town_id`"))) {
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        final int townId = resultSet.getInt("town_id");
                        final Town town = towns.stream().filter(t -> t.getId() == townId).findFirst().orElseThrow();
                        town.setClaimCount(resultSet.getInt("claims"));
                    }
                }
            }

            // Select the spawn for each town
            try (PreparedStatement statement = connection.prepareStatement(formatStatement("""
                    SELECT %towns%.`id` AS `town_id`, `is_spawn_public`, `server`, `world`, `x`, `y`, `z`, `yaw`, `pitch` FROM %towns%
                    INNER JOIN %locations%
                    ON %towns%.`spawn_location_id` = %locations%.`id`"""))) {
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        final int townId = resultSet.getInt("town_id");
                        final Town town = towns.stream().filter(t -> t.getId() == townId).findFirst().orElseThrow();
                        final String worldName = resultSet.getString("world");
                        final Spawn spawn = Spawn.of(Position.at(
                                        resultSet.getDouble("x"),
                                        resultSet.getDouble("y"),
                                        resultSet.getDouble("z"),
                                        World.of(new UUID(0, 0),
                                                worldName, determineEnvironment(worldName)),
                                        resultSet.getFloat("yaw"),
                                        resultSet.getFloat("pitch")),
                                resultSet.getString("server"));
                        spawn.setPublic(resultSet.getBoolean("is_spawn_public"));
                        town.setSpawn(spawn);
                    }
                }
            }

            // Select the total bonus_claims and bonus_members for each town based on ID from the %bonuses% table
            try (PreparedStatement statement = connection.prepareStatement(formatStatement("""
                    SELECT `town_id`, SUM(`bonus_claims`) AS `bonus_claims`, SUM(`bonus_members`) AS `bonus_members`
                    FROM %bonuses% GROUP BY `town_id`"""))) {
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        final int townId = resultSet.getInt("town_id");
                        final Town town = towns.stream().filter(t -> t.getId() == townId).findFirst().orElseThrow();
                        town.setBonusClaims(resultSet.getInt("bonus_claims"));
                        town.setBonusMembers(resultSet.getInt("bonus_members"));
                    }
                }
            }

            // Set the flag settings for each town
            try (PreparedStatement statement = connection.prepareStatement(
                    formatStatement("SELECT * FROM %flags%"))) {
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        final int townId = resultSet.getInt("town_id");
                        final Town town = towns.stream().filter(t -> t.getId() == townId).findFirst().orElseThrow();
                        final Claim.Type type = Claim.Type.values()[resultSet.getInt("chunk_type")];
                        town.getRules().put(type, Rules.of(Map.of(
                                Flag.EXPLOSION_DAMAGE, resultSet.getBoolean("explosion_damage"),
                                Flag.FIRE_DAMAGE, resultSet.getBoolean("fire_damage"),
                                Flag.MOB_GRIEFING, resultSet.getBoolean("mob_griefing"),
                                Flag.MONSTER_SPAWNING, resultSet.getBoolean("monster_spawning"),
                                Flag.PVP, resultSet.getBoolean("pvp"),
                                Flag.PUBLIC_INTERACT_ACCESS, resultSet.getBoolean("public_interact_access"),
                                Flag.PUBLIC_CONTAINER_ACCESS, resultSet.getBoolean("public_container_access"),
                                Flag.PUBLIC_BUILD_ACCESS, resultSet.getBoolean("public_build_access"),
                                Flag.PUBLIC_FARM_ACCESS, resultSet.getBoolean("public_farm_access"))));
                    }
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return towns;
    }


    // Get a map of world names to converted claim worlds
    @NotNull
    protected Map<ServerWorld, ClaimWorld> getConvertedClaimWorlds() {
        final Map<ServerWorld, ClaimWorld> claimWorlds = plugin.getDatabase().getClaimWorlds();
        try (Connection connection = getConnection()) {
            try (PreparedStatement statement = connection.prepareStatement(
                    formatStatement("SELECT * FROM %claims%"))) {
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        final String worldName = resultSet.getString("world");
                        final String serverName = resultSet.getString("server");
                        final ServerWorld serverWorld = new ServerWorld(serverName, World.of(
                                new UUID(0, 0),
                                worldName, determineEnvironment(worldName)));

                        final Optional<ClaimWorld> world = claimWorlds.entrySet()
                                .stream()
                                .filter(e -> e.getKey().equals(serverWorld))
                                .map(Map.Entry::getValue)
                                .findFirst();

                        if (world.isEmpty()) {
                            plugin.log(Level.WARNING, "Could not find claim world for " + serverWorld + "! " +
                                                      "Are all your servers online and running the latest HuskTowns version?");
                            continue;
                        }
                        final ClaimWorld claimWorld = world.get();
                        final Claim claim = Claim.at(Chunk.at(resultSet.getInt("chunk_x"),
                                resultSet.getInt("chunk_z")));
                        claim.setType(Claim.Type.values()[resultSet.getInt("chunk_type")]);

                        final int chunkId = resultSet.getInt("town_id");
                        if (claimWorld.getClaims().containsKey(chunkId)) {
                            claimWorld.getClaims().get(chunkId).add(claim);
                        } else {
                            claimWorld.getClaims().put(chunkId, new ArrayList<>(List.of(claim)));
                        }
                        claimWorlds.replaceAll((k, v) -> k.equals(serverWorld) ? claimWorld : v);
                    }
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return claimWorlds;
    }

    @NotNull
    private String determineEnvironment(@NotNull String worldName) {
        if (worldName.endsWith("_nether")) {
            return "nether";
        }
        if (worldName.endsWith("_the_end")) {
            return "end";
        }
        return "normal";
    }

    @NotNull
    private Connection getConnection() throws SQLException {
        final Database.Type type = getParameter(Parameters.LEGACY_DATABASE_TYPE.name())
                .map(String::toUpperCase).map(Database.Type::valueOf).orElse(Database.Type.MYSQL);
        final String host = getParameter(Parameters.LEGACY_DATABASE_HOST.name()).orElse("localhost");
        final int port = getParameter(Parameters.LEGACY_DATABASE_PORT.name()).map(Integer::parseInt).orElse(3306);
        final String database = getParameter(Parameters.LEGACY_DATABASE_NAME.name()).orElse("HuskTowns");
        final String username = getParameter(Parameters.LEGACY_DATABASE_USERNAME.name()).orElse("root");
        final String password = getParameter(Parameters.LEGACY_DATABASE_PASSWORD.name()).orElse("");

        if (type == Database.Type.MYSQL) {
            return DriverManager.getConnection("jdbc:mysql://" + host + ":" + port + "/" + database, username, password);
        }
        return DriverManager.getConnection("jdbc:sqlite:" + new File(plugin.getDataFolder(), "HuskTownsData.db").getAbsolutePath());
    }

    @NotNull
    private String formatStatement(@NotNull String statement) {
        return statement.replaceAll("%players%", getParameter(Parameters.LEGACY_PLAYERS_TABLE.name()).orElse("husktowns_players"))
                .replaceAll("%towns%", getParameter(Parameters.LEGACY_TOWNS_TABLE.name()).orElse("husktowns_towns"))
                .replaceAll("%plot_members%", getParameter(Parameters.LEGACY_PLOT_MEMBERS_TABLE.name()).orElse("husktowns_plot_members"))
                .replaceAll("%claims%", getParameter(Parameters.LEGACY_CLAIMS_TABLE.name()).orElse("husktowns_claims"))
                .replaceAll("%flags%", getParameter(Parameters.LEGACY_FLAGS_TABLE.name()).orElse("husktowns_flags"))
                .replaceAll("%locations%", getParameter(Parameters.LEGACY_LOCATIONS_TABLE.name()).orElse("husktowns_locations"))
                .replaceAll("%bonuses%", getParameter(Parameters.LEGACY_BONUSES_TABLE.name()).orElse("husktowns_bonus"));
    }

    /**
     * Parameters for carrying out a legacy migration
     */
    protected enum Parameters {
        LEGACY_DATABASE_TYPE("MySQL"),
        LEGACY_DATABASE_HOST("localhost"),
        LEGACY_DATABASE_PORT("3306"),
        LEGACY_DATABASE_NAME("HuskTowns"),
        LEGACY_DATABASE_USERNAME("root"),
        LEGACY_DATABASE_PASSWORD("pa55w0rd"),
        LEGACY_PLAYERS_TABLE("husktowns_players"),
        LEGACY_TOWNS_TABLE("husktowns_towns"),
        LEGACY_PLOT_MEMBERS_TABLE("husktowns_plot_members"),
        LEGACY_CLAIMS_TABLE("husktowns_claims"),
        LEGACY_FLAGS_TABLE("husktowns_flags"),
        LEGACY_LOCATIONS_TABLE("husktowns_locations"),
        LEGACY_BONUSES_TABLE("husktowns_bonus");

        private final String defaultValue;

        Parameters(@NotNull String defaultValue) {
            this.defaultValue = defaultValue;
        }

        @NotNull
        private String getDefault() {
            return defaultValue;
        }

    }

}