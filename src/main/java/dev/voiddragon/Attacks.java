package dev.voiddragon;

import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.entity.DragonFireball;
import org.bukkit.entity.EnderDragon;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * Every active attack of the dragon. Attacks are additive: phase 1 uses tier 0 attacks,
 * phase 2 adds tier 1, phase 3 adds tier 2. All of them are telegraphed so they can be dodged.
 */
final class Attacks {

    enum Type {
        FIREBALLS(0),   // dragon spits dragon-breath fireballs at players
        LIGHTNING(0),   // marked spots get struck after a short warning
        BEAM(1),        // a void laser sweeps across the ground
        SHOCKWAVE(1),   // expanding ring on the ground - jump it
        GRAVITY(2),     // black hole pulls everyone in, then explodes
        METEORS(2);     // rain of falling strikes

        final int minTier;
        Type(int minTier) { this.minTier = minTier; }
    }

    private static final Color RED = Color.fromRGB(255, 40, 90);
    private static final Color ORANGE = Color.fromRGB(255, 150, 30);
    private static final Color PURPLE = Color.fromRGB(190, 70, 255);
    private static final Color YELLOW = Color.fromRGB(255, 240, 80);

    private final VoidDragonPlugin plugin;
    private final EnderDragon dragon;
    private final World world;
    private final Random rnd;

    Attacks(VoidDragonPlugin plugin, EnderDragon dragon, World world, Random rnd) {
        this.plugin = plugin;
        this.dragon = dragon;
        this.world = world;
        this.rnd = rnd;
    }

    void run(Type type, int tier) {
        switch (type) {
            case FIREBALLS -> fireballs(tier);
            case LIGHTNING -> lightning(tier);
            case BEAM -> beam(tier);
            case SHOCKWAVE -> shockwave(tier);
            case GRAVITY -> gravity(tier);
            case METEORS -> meteors(tier);
        }
    }

    // ------------------------------------------------------------------ helpers

    private boolean hittable(Player p) {
        return !p.isDead() && p.getWorld().equals(world)
                && p.getGameMode() != GameMode.SPECTATOR && p.getGameMode() != GameMode.CREATIVE;
    }

    private List<Player> targets() {
        List<Player> out = new ArrayList<>();
        for (Player p : world.getPlayers()) {
            if (hittable(p) && p.getLocation().distanceSquared(dragon.getLocation()) < 160 * 160) out.add(p);
        }
        return out;
    }

    private Player randomTarget() {
        List<Player> ts = targets();
        return ts.isEmpty() ? null : ts.get(rnd.nextInt(ts.size()));
    }

    private void dust(Location at, Color color, float size) {
        world.spawnParticle(Particle.DUST, at, 1, 0, 0, 0, 0, new Particle.DustOptions(color, size));
    }

    private void ring(Location center, double r, int points, Color color) {
        for (int i = 0; i < points; i++) {
            double a = Math.PI * 2 * i / points;
            dust(center.clone().add(Math.cos(a) * r, 0, Math.sin(a) * r), color, 1.3f);
        }
    }

    /** A circle appears under the target spot, closes in, then blasts everyone inside. */
    private void strike(Location spot, double radius, double damage, int telegraph, Color color, boolean fromSky) {
        new BukkitRunnable() {
            int t = 0;
            @Override public void run() {
                if (!dragon.isValid()) { cancel(); return; }
                double progress = t / (double) telegraph;
                ring(spot.clone().add(0, 0.2, 0), radius * (1.0 + (1.0 - progress)), 24, color);
                ring(spot.clone().add(0, 0.2, 0), radius, 24, color);
                if (fromSky) {
                    world.spawnParticle(Particle.FLAME, spot.clone().add(0, 14 * (1 - progress), 0), 6, 0.3, 0.3, 0.3, 0.02);
                }
                if (t % 8 == 0) {
                    world.playSound(spot, Sound.BLOCK_RESPAWN_ANCHOR_CHARGE, SoundCategory.HOSTILE, 1.2f, 0.5f + (float) progress);
                }
                if (++t >= telegraph) {
                    cancel();
                    world.strikeLightningEffect(spot);
                    world.spawnParticle(Particle.EXPLOSION_EMITTER, spot, 1);
                    world.spawnParticle(Particle.REVERSE_PORTAL, spot, 60, radius / 2, 1, radius / 2, 0.3);
                    world.playSound(spot, Sound.ENTITY_GENERIC_EXPLODE, SoundCategory.HOSTILE, 3f, 0.8f);
                    for (Player p : world.getPlayers()) {
                        if (!hittable(p)) continue;
                        Location pl = p.getLocation();
                        double dx = pl.getX() - spot.getX(), dz = pl.getZ() - spot.getZ();
                        if (dx * dx + dz * dz <= radius * radius && Math.abs(pl.getY() - spot.getY()) <= 4) {
                            p.damage(damage, dragon);
                        }
                    }
                }
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    // ------------------------------------------------------------------ FIREBALLS

    private void fireballs(int tier) {
        if (targets().isEmpty()) return;
        final int shots = 4 + tier * 2;
        world.playSound(dragon.getLocation(), Sound.ENTITY_ENDER_DRAGON_GROWL, SoundCategory.HOSTILE, 6f, 1.2f);
        new BukkitRunnable() {
            int i = 0;
            @Override public void run() {
                if (!dragon.isValid()) { cancel(); return; }
                Player p = randomTarget();
                if (p != null) {
                    Location from = dragon.getLocation().add(0, 1, 0);
                    Vector dir = p.getLocation().add(0, 1, 0).toVector().subtract(from.toVector());
                    if (dir.lengthSquared() > 1) {
                        dir.normalize();
                        from.add(dir.clone().multiply(7));   // spawn clear of the dragon's own hitbox
                        world.spawn(from, DragonFireball.class, fb -> {
                            fb.setShooter(dragon);
                            fb.setDirection(dir);
                        });
                        world.playSound(from, Sound.ENTITY_ENDER_DRAGON_SHOOT, SoundCategory.HOSTILE, 4f, 1f);
                        world.spawnParticle(Particle.DRAGON_BREATH, from, 25, 0.5, 0.5, 0.5, 0.05);
                    }
                }
                if (++i >= shots) cancel();
            }
        }.runTaskTimer(plugin, 0L, 12L);
    }

    // ------------------------------------------------------------------ LIGHTNING

    private void lightning(int tier) {
        List<Player> ts = targets();
        if (ts.isEmpty()) return;
        world.playSound(dragon.getLocation(), Sound.ENTITY_LIGHTNING_BOLT_THUNDER, SoundCategory.HOSTILE, 6f, 0.7f);
        int strikes = 4 + tier * 3;
        for (int i = 0; i < strikes; i++) {
            final int delay = i * 6;
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (!dragon.isValid()) return;
                Player p = randomTarget();
                if (p == null) return;
                Location spot = p.getLocation().clone().add(rnd.nextDouble() * 8 - 4, 0, rnd.nextDouble() * 8 - 4);
                strike(spot, 2.5, 8.0, 30, YELLOW, true);
            }, delay);
        }
    }

    // ------------------------------------------------------------------ BEAM

    private void beam(int tier) {
        Player target = randomTarget();
        if (target == null) return;
        final Location base = target.getLocation().clone();
        double ang = rnd.nextDouble() * Math.PI * 2;
        Vector side = new Vector(Math.cos(ang), 0, Math.sin(ang));
        final Location start = base.clone().add(side.clone().multiply(-15));
        final Location end = base.clone().add(side.clone().multiply(15));
        final int telegraph = 36;
        final int sweep = 56 - tier * 6;
        final double damage = 6.0 + tier * 1.5;

        world.playSound(dragon.getLocation(), Sound.ENTITY_ENDER_DRAGON_GROWL, SoundCategory.HOSTILE, 6f, 0.6f);

        new BukkitRunnable() {
            int t = 0;
            @Override public void run() {
                if (!dragon.isValid()) { cancel(); return; }
                Location from = dragon.getLocation().add(0, 1, 0);

                if (t < telegraph) {
                    // warning: show the sweep path on the ground
                    if (t % 3 == 0) {
                        for (int i = 0; i <= 30; i++) {
                            dust(start.clone().add(end.clone().subtract(start).toVector().multiply(i / 30.0)).add(0, 0.2, 0), RED, 1.2f);
                        }
                    }
                    if (t % 10 == 0) world.playSound(start, Sound.BLOCK_BEACON_POWER_SELECT, SoundCategory.HOSTILE, 2f, 0.5f + t / 40f);
                } else {
                    double progress = (t - telegraph) / (double) sweep;
                    Location pos = start.clone().add(end.clone().subtract(start).toVector().multiply(progress));
                    Vector line = pos.toVector().subtract(from.toVector());
                    double len = line.length();
                    if (len > 0.1) {
                        Vector step = line.clone().normalize().multiply(0.8);
                        Location cur = from.clone();
                        for (double d = 0; d < len; d += 0.8) {
                            dust(cur, PURPLE, 1.6f);
                            cur.add(step);
                        }
                    }
                    world.spawnParticle(Particle.END_ROD, pos, 8, 0.6, 0.2, 0.6, 0.08);
                    world.spawnParticle(Particle.REVERSE_PORTAL, pos, 20, 0.8, 0.2, 0.8, 0.3);
                    if (t % 3 == 0) world.playSound(pos, Sound.ENTITY_GENERIC_BURN, SoundCategory.HOSTILE, 1.5f, 0.6f);
                    if (t % 4 == 0) {
                        for (Player p : world.getPlayers()) {
                            if (!hittable(p)) continue;
                            double dist = Util.distToSegment(p.getLocation().add(0, 1, 0).toVector(),
                                    from.toVector(), pos.toVector());
                            if (dist < 1.8) {
                                p.setNoDamageTicks(0);
                                p.damage(damage, dragon);
                            }
                        }
                    }
                }
                if (++t >= telegraph + sweep) cancel();
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    // ------------------------------------------------------------------ SHOCKWAVE

    private void shockwave(int tier) {
        Player target = randomTarget();
        if (target == null) return;
        final Location center = target.getLocation().clone();
        world.playSound(center, Sound.ENTITY_ENDER_DRAGON_GROWL, SoundCategory.HOSTILE, 6f, 0.5f);
        sendWave(center, 0);
        if (tier >= 2) sendWave(center, 24);   // second wave in the last phase
    }

    private void sendWave(Location center, int delay) {
        new BukkitRunnable() {
            int t = -20 - delay;          // 1 second warning before it moves
            final Set<UUID> hit = new HashSet<>();
            @Override public void run() {
                if (!dragon.isValid()) { cancel(); return; }
                if (t < 0) {
                    if (t % 4 == 0) {
                        world.spawnParticle(Particle.REVERSE_PORTAL, center, 30, 1, 0.1, 1, 0.3);
                        ring(center.clone().add(0, 0.2, 0), 2.0, 16, ORANGE);
                    }
                    t++;
                    return;
                }
                double r = 1.5 + t * 0.55;
                ring(center.clone().add(0, 0.25, 0), r, (int) (r * 6), ORANGE);
                world.spawnParticle(Particle.EXPLOSION, center.clone().add(
                        Math.cos(t) * r, 0.3, Math.sin(t) * r), 1);
                if (t % 4 == 0) world.playSound(center, Sound.ENTITY_WARDEN_SONIC_BOOM, SoundCategory.HOSTILE, 0.8f, 1.5f);
                for (Player p : world.getPlayers()) {
                    if (!hittable(p) || hit.contains(p.getUniqueId())) continue;
                    Location pl = p.getLocation();
                    double dx = pl.getX() - center.getX(), dz = pl.getZ() - center.getZ();
                    double dist = Math.sqrt(dx * dx + dz * dz);
                    double height = pl.getY() - center.getY();
                    // players that are in the air (jumped) are not hit
                    if (Math.abs(dist - r) < 1.2 && height < 0.9 && height > -4) {
                        hit.add(p.getUniqueId());
                        p.damage(7.0, dragon);
                        Vector push = new Vector(dx, 0, dz);
                        if (push.lengthSquared() > 0.001) {
                            p.setVelocity(p.getVelocity().add(push.normalize().multiply(0.9).setY(0.55)));
                        }
                    }
                }
                if (++t > 40) cancel();
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    // ------------------------------------------------------------------ GRAVITY WELL

    private void gravity(int tier) {
        Player target = randomTarget();
        if (target == null) return;
        final Location well = target.getLocation().clone().add(0, 4, 0);
        final int duration = 90;
        final double blastRadius = 6.0;

        world.playSound(well, Sound.ENTITY_ENDER_DRAGON_GROWL, SoundCategory.HOSTILE, 6f, 0.4f);
        for (Player p : world.getPlayers()) {
            if (hittable(p)) p.sendActionBar(net.kyori.adventure.text.Component.text(
                    "A gravity well! Break away before it collapses!", net.kyori.adventure.text.format.NamedTextColor.DARK_PURPLE));
        }

        new BukkitRunnable() {
            int t = 0;
            @Override public void run() {
                if (!dragon.isValid()) { cancel(); return; }
                double progress = t / (double) duration;

                // swirling accretion disc
                for (int i = 0; i < 3; i++) {
                    double a = t * 0.35 + i * (Math.PI * 2 / 3);
                    double r = 7 * (1 - progress) + 1;
                    Location p = well.clone().add(Math.cos(a) * r, Math.sin(a * 0.5) * 0.8, Math.sin(a) * r);
                    world.spawnParticle(Particle.PORTAL, p, 4, 0.1, 0.1, 0.1, 0.1);
                    dust(p, Color.fromRGB(20, 0, 40), 2.2f);
                }
                world.spawnParticle(Particle.SQUID_INK, well, 6, 0.4, 0.4, 0.4, 0.02);
                if (t % 10 == 0) world.playSound(well, Sound.BLOCK_PORTAL_AMBIENT, SoundCategory.HOSTILE, 2f, 0.5f + (float) progress);

                // pull
                double pull = 0.05 + 0.07 * progress + tier * 0.01;
                for (Player p : world.getPlayers()) {
                    if (!hittable(p)) continue;
                    Vector to = well.toVector().subtract(p.getLocation().toVector());
                    double d = to.length();
                    if (d < 18 && d > 1.5) {
                        p.setVelocity(p.getVelocity().add(to.normalize().multiply(pull)));
                    }
                }

                if (++t >= duration) {
                    cancel();
                    world.spawnParticle(Particle.EXPLOSION_EMITTER, well, 3, 1.5, 1.5, 1.5, 0);
                    world.spawnParticle(Particle.REVERSE_PORTAL, well, 150, 3, 3, 3, 0.5);
                    world.strikeLightningEffect(well);
                    world.playSound(well, Sound.ENTITY_GENERIC_EXPLODE, SoundCategory.HOSTILE, 5f, 0.5f);
                    for (Player p : world.getPlayers()) {
                        if (!hittable(p)) continue;
                        double d = p.getLocation().distance(well);
                        if (d <= blastRadius) {
                            p.damage(12.0, dragon);
                            Vector out = p.getLocation().toVector().subtract(well.toVector());
                            if (out.lengthSquared() > 0.001) {
                                p.setVelocity(out.normalize().multiply(1.3).setY(0.6));
                            }
                        }
                    }
                }
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    // ------------------------------------------------------------------ METEORS

    private void meteors(int tier) {
        if (targets().isEmpty()) return;
        world.playSound(dragon.getLocation(), Sound.ENTITY_ENDER_DRAGON_GROWL, SoundCategory.HOSTILE, 6f, 0.8f);
        int count = 10 + tier * 2;
        for (int i = 0; i < count; i++) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (!dragon.isValid()) return;
                Player p = randomTarget();
                if (p == null) return;
                Location spot = p.getLocation().clone().add(rnd.nextDouble() * 12 - 6, 0, rnd.nextDouble() * 12 - 6);
                strike(spot, 3.0, 9.0, 28, ORANGE, true);
            }, i * 5L);
        }
    }
}
