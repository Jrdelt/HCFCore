package me.vertex.core.util;

import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/** A short renewable flight effect; landing stops renewal, never removes another source's potion. */
public final class FlightEffects {
    private FlightEffects() { }
    public static void renewSlowFall(Player player) {
        PotionEffect current=player.getPotionEffect(PotionEffectType.SLOW_FALLING);
        if(current!=null&&(current.isInfinite()||current.getDuration()>=40||current.getAmplifier()>0))return;
        player.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING,100,0,false,false,false));
    }
}
