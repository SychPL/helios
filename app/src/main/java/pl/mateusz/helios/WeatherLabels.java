package pl.mateusz.helios;
public final class WeatherLabels {
    private WeatherLabels(){}
    public static String polish(String state){
        switch(state){
            case "clear-night":return I18n.t("Pogodna noc");
            case "cloudy":return I18n.t("Pochmurno");
            case "fog":return I18n.t("Mgła");
            case "hail":return I18n.t("Grad");
            case "lightning":return I18n.t("Burza");
            case "lightning-rainy":return I18n.t("Burza z deszczem");
            case "partlycloudy":return I18n.t("Częściowe zachmurzenie");
            case "pouring":return I18n.t("Ulewa");
            case "rainy":return I18n.t("Deszcz");
            case "snowy":return I18n.t("Śnieg");
            case "snowy-rainy":return I18n.t("Deszcz ze śniegiem");
            case "sunny":return I18n.t("Słonecznie");
            case "windy":case "windy-variant":return I18n.t("Wietrznie");
            case "exceptional":return I18n.t("Nietypowe warunki");
            default:return I18n.t("Brak opisu pogody");
        }
    }
    /** The Material Design icon Home Assistant itself uses for a weather condition; an unknown condition gets none. */
    public static String icon(String state){
        if(state==null)return null;
        switch(state){
            case "clear-night":return "mdi:weather-night";
            case "cloudy":return "mdi:weather-cloudy";
            case "fog":return "mdi:weather-fog";
            case "hail":return "mdi:weather-hail";
            case "lightning":return "mdi:weather-lightning";
            case "lightning-rainy":return "mdi:weather-lightning-rainy";
            case "partlycloudy":return "mdi:weather-partly-cloudy";
            case "pouring":return "mdi:weather-pouring";
            case "rainy":return "mdi:weather-rainy";
            case "snowy":return "mdi:weather-snowy";
            case "snowy-rainy":return "mdi:weather-snowy-rainy";
            case "sunny":return "mdi:weather-sunny";
            case "windy":case "windy-variant":return "mdi:weather-windy";
            case "exceptional":return "mdi:alert-circle-outline";
            default:return null;
        }
    }
}
