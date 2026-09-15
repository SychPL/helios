package pl.mateusz.helios;

/** HA brightness (1..255) to the dock's ten lamp levels and back. Zero brightness is turn_off, never level 0. */
final class LampMath {
    private LampMath(){}
    static int brightnessToLevel(int brightness){return Math.max(1,Math.min(10,(int)Math.round(brightness*10/255.0)));}
    static int levelToBrightness(int level){return Math.max(1,Math.min(255,(int)Math.round(level*255/10.0)));}
}
