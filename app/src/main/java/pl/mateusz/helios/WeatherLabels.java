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
}
