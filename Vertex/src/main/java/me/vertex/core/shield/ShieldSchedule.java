package me.vertex.core.shield;

import java.time.ZonedDateTime;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** Seven independent daily windows; duration zero disables that day. */
public record ShieldSchedule(List<Window> days, boolean pvpProtected) {
    public ShieldSchedule {
        List<Window> normalized = new ArrayList<>(7);
        for (int i=0;i<7;i++) normalized.add(days != null && i < days.size() && days.get(i) != null ? days.get(i) : Window.DISABLED);
        days = List.copyOf(normalized);
    }
    public static ShieldSchedule empty(){return new ShieldSchedule(List.of(),false);}
    public Window day(int mondayBasedIndex){return days.get(Math.floorMod(mondayBasedIndex,7));}
    public ShieldSchedule withDay(int index,Window value){List<Window> copy=new ArrayList<>(days);copy.set(Math.floorMod(index,7),value);return new ShieldSchedule(copy,pvpProtected);}
    public ShieldSchedule withPvpProtected(boolean value){return new ShieldSchedule(days,value);}
    /** Effective protected minutes in one calendar day, including yesterday's spill-over. */
    public int protectedMinutes(int mondayBasedIndex){
        boolean[] protectedMinute=new boolean[1440];
        Window current=day(mondayBasedIndex);
        for(int minute=current.startMinute();minute<Math.min(1440,current.endMinute());minute++)protectedMinute[minute]=true;
        Window previous=day(mondayBasedIndex-1);
        if(previous.crossesMidnight())for(int minute=0;minute<Math.min(1440,previous.endMinute()-1440);minute++)protectedMinute[minute]=true;
        int total=0;for(boolean protectedNow:protectedMinute)if(protectedNow)total++;return total;
    }
    public boolean activeAt(ZonedDateTime now){int today=now.getDayOfWeek().getValue()-1;int minute=now.getHour()*60+now.getMinute();Window current=day(today);if(current.contains(minute))return true;Window previous=day(today-1);return previous.crossesMidnight()&&minute<previous.endMinute()-1440;}
    public long secondsUntilWindowEnd(ZonedDateTime now){if(!activeAt(now))return 0L;int today=now.getDayOfWeek().getValue()-1;int minute=now.getHour()*60+now.getMinute();Window window=day(today);int remaining=window.contains(minute)?window.endMinute()-minute:day(today-1).endMinute()-1440-minute;return Math.max(0L,remaining*60L-now.getSecond());}
    public long secondsUntilNextStart(ZonedDateTime now){
        for(int offset=0;offset<=7;offset++){
            ZonedDateTime date=now.plusDays(offset).toLocalDate().atStartOfDay(now.getZone());
            Window window=day(date.getDayOfWeek().getValue()-1);
            if(window.durationMinutes()<=0)continue;
            ZonedDateTime start=date.plusMinutes(window.startMinute());
            if(!start.isAfter(now))continue;
            return Math.max(0L,Duration.between(now,start).getSeconds());
        }
        return 0L;
    }
    public String encode(){StringBuilder out=new StringBuilder();for(int i=0;i<7;i++){if(i>0)out.append(';');Window window=day(i);out.append(window.startMinute()).append(',').append(window.durationMinutes());}return out.toString();}
    public static ShieldSchedule decode(String raw,boolean pvp){if(raw==null||raw.isBlank())return new ShieldSchedule(List.of(),pvp);List<Window> values=new ArrayList<>();for(String token:raw.split(";",-1)){String[] pair=token.split(",",-1);try{values.add(new Window(Integer.parseInt(pair[0]),Integer.parseInt(pair[1])));}catch(Exception ignored){values.add(Window.DISABLED);}}return new ShieldSchedule(values,pvp);}
    public record Window(int startMinute,int durationMinutes){public static final Window DISABLED=new Window(0,0);public Window{startMinute=Math.floorMod(startMinute,1440);durationMinutes=Math.max(0,Math.min(1440,durationMinutes));}boolean contains(int minute){return durationMinutes>0&&minute>=startMinute&&minute<Math.min(1440,endMinute());}boolean crossesMidnight(){return durationMinutes>0&&endMinute()>1440;}int endMinute(){return startMinute+durationMinutes;}}
}
