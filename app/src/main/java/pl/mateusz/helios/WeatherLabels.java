package pl.mateusz.helios;
public final class WeatherLabels {
    private WeatherLabels(){}
    public static String polish(String state){
        switch(state){
            case "clear-night":return "Pogodna noc";
            case "cloudy":return "Pochmurno";
            case "fog":return "Mgła";
            case "hail":return "Grad";
            case "lightning":return "Burza";
            case "lightning-rainy":return "Burza z deszczem";
            case "partlycloudy":return "Częściowe zachmurzenie";
            case "pouring":return "Ulewa";
            case "rainy":return "Deszcz";
            case "snowy":return "Śnieg";
            case "snowy-rainy":return "Deszcz ze śniegiem";
            case "sunny":return "Słonecznie";
            case "windy":case "windy-variant":return "Wietrznie";
            case "exceptional":return "Nietypowe warunki";
            default:return "Brak opisu pogody";
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
