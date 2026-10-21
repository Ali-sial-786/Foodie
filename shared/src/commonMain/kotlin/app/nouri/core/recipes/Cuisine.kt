package app.nouri.core.recipes

import kotlinx.serialization.Serializable

/**
 * A cuisine with its regional traditions. Regions are first-class so that, e.g.,
 * Sindhi biryani and Hyderabadi biryani are not collapsed into "Indian food".
 */
@Serializable
data class Cuisine(
    val id: String,
    val name: String,
    val country: String,
    val regions: List<String> = emptyList(),
    val worldRegion: WorldRegion,
)

@Serializable
enum class WorldRegion(val label: String) {
    SOUTH_ASIA("South Asia"),
    CENTRAL_WEST_ASIA("Central & West Asia"),
    MIDDLE_EAST("Middle East"),
    NORTH_AFRICA("North Africa"),
    SUB_SAHARAN_AFRICA("Sub-Saharan Africa"),
    EAST_ASIA("East Asia"),
    SOUTHEAST_ASIA("Southeast Asia"),
    LATIN_AMERICA("Latin America & Caribbean"),
    EUROPE("Europe"),
    NORTH_AMERICA("North America"),
    OCEANIA("Oceania"),
}

object CuisineCatalog {
    val all: List<Cuisine> = listOf(
        Cuisine("pakistani", "Pakistani", "Pakistan",
            listOf("Punjabi", "Sindhi", "Pashtun (Khyber Pakhtunkhwa)", "Balochi", "Karachi / Muhajir", "Kashmiri", "Hunza / Gilgit-Baltistan", "Lahori"),
            WorldRegion.SOUTH_ASIA),
        Cuisine("indian", "Indian", "India",
            listOf("Punjabi", "Mughlai / Awadhi", "Hyderabadi", "Bengali", "Gujarati", "Rajasthani", "Maharashtrian", "Goan",
                "Kerala", "Tamil / Chettinad", "Kashmiri", "Andhra", "Udupi / Karnataka", "Bihari", "Northeastern"),
            WorldRegion.SOUTH_ASIA),
        Cuisine("bangladeshi", "Bangladeshi", "Bangladesh", listOf("Dhaka (Puran Dhaka)", "Chittagonian", "Sylheti"), WorldRegion.SOUTH_ASIA),
        Cuisine("sri_lankan", "Sri Lankan", "Sri Lanka", listOf("Sinhalese", "Tamil (Jaffna)", "Muslim / Malay"), WorldRegion.SOUTH_ASIA),
        Cuisine("nepali", "Nepali", "Nepal", listOf("Newari", "Thakali", "Himalayan / Sherpa"), WorldRegion.SOUTH_ASIA),
        Cuisine("maldivian", "Maldivian", "Maldives", emptyList(), WorldRegion.SOUTH_ASIA),
        Cuisine("afghan", "Afghan", "Afghanistan", listOf("Kabuli", "Herati", "Kandahari", "Uzbek-Afghan"), WorldRegion.CENTRAL_WEST_ASIA),
        Cuisine("central_asian", "Central Asian", "Central Asia", listOf("Uzbek", "Kazakh", "Kyrgyz", "Tajik", "Turkmen", "Uyghur"), WorldRegion.CENTRAL_WEST_ASIA),
        Cuisine("georgian", "Georgian", "Georgia", listOf("Imeretian", "Megrelian (Samegrelo)", "Kakhetian", "Adjarian"), WorldRegion.CENTRAL_WEST_ASIA),
        Cuisine("armenian", "Armenian", "Armenia", listOf("Yerevan", "Western Armenian (diaspora)"), WorldRegion.CENTRAL_WEST_ASIA),
        Cuisine("azerbaijani", "Azerbaijani", "Azerbaijan", listOf("Baku / Absheron", "Lankaran", "Sheki"), WorldRegion.CENTRAL_WEST_ASIA),
        Cuisine("iranian", "Iranian (Persian)", "Iran", listOf("Tehrani", "Gilani (Caspian)", "Azerbaijani-Iranian", "Shirazi", "Khorasani"), WorldRegion.MIDDLE_EAST),
        Cuisine("turkish", "Turkish", "Türkiye", listOf("Anatolian", "Aegean", "Black Sea", "Southeastern (Gaziantep, Urfa)", "Ottoman / Istanbul"), WorldRegion.MIDDLE_EAST),
        Cuisine("lebanese", "Lebanese", "Lebanon", listOf("Beiruti", "Mountain", "Tripoli"), WorldRegion.MIDDLE_EAST),
        Cuisine("syrian", "Syrian", "Syria", listOf("Aleppine", "Damascene"), WorldRegion.MIDDLE_EAST),
        Cuisine("palestinian", "Palestinian", "Palestine", listOf("Galilean", "Gazan", "West Bank (Nablus, Hebron)"), WorldRegion.MIDDLE_EAST),
        Cuisine("jordanian", "Jordanian", "Jordan", listOf("Bedouin", "Ammani"), WorldRegion.MIDDLE_EAST),
        Cuisine("iraqi", "Iraqi", "Iraq", listOf("Baghdadi", "Mosul (Maslawi)", "Basra", "Kurdish"), WorldRegion.MIDDLE_EAST),
        Cuisine("gulf", "Gulf Arab (Khaleeji)", "Arabian Gulf", listOf("Saudi (Najdi, Hejazi)", "Emirati", "Kuwaiti", "Omani", "Qatari", "Bahraini"), WorldRegion.MIDDLE_EAST),
        Cuisine("yemeni", "Yemeni", "Yemen", listOf("Sana'ani", "Hadhrami", "Adeni"), WorldRegion.MIDDLE_EAST),
        Cuisine("israeli", "Israeli", "Israel", listOf("Mizrahi", "Sephardi", "Ashkenazi"), WorldRegion.MIDDLE_EAST),
        Cuisine("egyptian", "Egyptian", "Egypt", listOf("Cairene", "Alexandrian", "Upper Egyptian (Saidi)"), WorldRegion.NORTH_AFRICA),
        Cuisine("moroccan", "Moroccan", "Morocco", listOf("Fassi", "Marrakchi", "Berber / Amazigh", "Tangier"), WorldRegion.NORTH_AFRICA),
        Cuisine("tunisian", "Tunisian", "Tunisia", listOf("Tunis", "Sfaxian", "Djerban"), WorldRegion.NORTH_AFRICA),
        Cuisine("algerian", "Algerian", "Algeria", listOf("Algiers", "Kabyle", "Oranais", "Constantinois"), WorldRegion.NORTH_AFRICA),
        Cuisine("libyan", "Libyan", "Libya", listOf("Tripolitanian", "Cyrenaican"), WorldRegion.NORTH_AFRICA),
        Cuisine("sudanese", "Sudanese", "Sudan", listOf("Khartoum", "Nubian", "Darfuri"), WorldRegion.NORTH_AFRICA),
        Cuisine("ethiopian", "Ethiopian", "Ethiopia", listOf("Amhara", "Tigrayan", "Oromo", "Gurage"), WorldRegion.SUB_SAHARAN_AFRICA),
        Cuisine("nigerian", "Nigerian", "Nigeria", listOf("Yoruba", "Igbo", "Hausa", "Efik / Calabar"), WorldRegion.SUB_SAHARAN_AFRICA),
        Cuisine("ghanaian", "Ghanaian", "Ghana", listOf("Akan / Ashanti", "Ewe", "Ga", "Northern"), WorldRegion.SUB_SAHARAN_AFRICA),
        Cuisine("kenyan", "Kenyan", "Kenya", listOf("Swahili coast", "Kikuyu", "Luo"), WorldRegion.SUB_SAHARAN_AFRICA),
        Cuisine("eritrean", "Eritrean", "Eritrea", listOf("Tigrinya", "Asmara"), WorldRegion.SUB_SAHARAN_AFRICA),
        Cuisine("somali", "Somali", "Somalia", listOf("Mogadishu (Benadir)", "Northern (Somaliland)"), WorldRegion.SUB_SAHARAN_AFRICA),
        Cuisine("tanzanian", "Tanzanian", "Tanzania", listOf("Zanzibari", "Swahili coast", "Mainland"), WorldRegion.SUB_SAHARAN_AFRICA),
        Cuisine("ugandan", "Ugandan", "Uganda", listOf("Buganda", "Western"), WorldRegion.SUB_SAHARAN_AFRICA),
        Cuisine("senegalese", "Senegalese", "Senegal", listOf("Wolof", "Saint-Louis", "Casamance"), WorldRegion.SUB_SAHARAN_AFRICA),
        Cuisine("ivorian", "Ivorian", "Côte d'Ivoire", listOf("Abidjan", "Baoulé"), WorldRegion.SUB_SAHARAN_AFRICA),
        Cuisine("cameroonian", "Cameroonian", "Cameroon", listOf("Douala (Littoral)", "Bamiléké (West)", "Northern"), WorldRegion.SUB_SAHARAN_AFRICA),
        Cuisine("congolese", "Congolese", "DR Congo / Congo", listOf("Kinshasa", "Brazzaville"), WorldRegion.SUB_SAHARAN_AFRICA),
        Cuisine("south_african", "South African", "South Africa", listOf("Cape Malay", "Afrikaner", "Durban Indian", "Xhosa / Zulu"), WorldRegion.SUB_SAHARAN_AFRICA),
        Cuisine("chinese", "Chinese", "China",
            listOf("Sichuan", "Cantonese", "Hunan", "Shandong", "Jiangsu (Huaiyang)", "Zhejiang", "Fujian", "Anhui", "Xinjiang / Uyghur", "Dongbei", "Shanghainese", "Hakka"),
            WorldRegion.EAST_ASIA),
        Cuisine("japanese", "Japanese", "Japan", listOf("Kanto", "Kansai", "Hokkaido", "Okinawan", "Kyushu"), WorldRegion.EAST_ASIA),
        Cuisine("korean", "Korean", "Korea", listOf("Seoul", "Jeolla", "Gyeongsang", "Jeju", "Temple (sachal)"), WorldRegion.EAST_ASIA),
        Cuisine("taiwanese", "Taiwanese", "Taiwan", listOf("Tainan", "Taipei", "Hakka-Taiwanese"), WorldRegion.EAST_ASIA),
        Cuisine("hong_kong", "Hong Kong", "Hong Kong", listOf("Cha chaan teng", "Dim sum"), WorldRegion.EAST_ASIA),
        Cuisine("mongolian", "Mongolian", "Mongolia", emptyList(), WorldRegion.EAST_ASIA),
        Cuisine("tibetan", "Tibetan", "Tibet / Himalaya", emptyList(), WorldRegion.EAST_ASIA),
        Cuisine("thai", "Thai", "Thailand", listOf("Central", "Northern (Lanna)", "Northeastern (Isan)", "Southern"), WorldRegion.SOUTHEAST_ASIA),
        Cuisine("vietnamese", "Vietnamese", "Vietnam", listOf("Northern (Hanoi)", "Central (Hue)", "Southern (Saigon)"), WorldRegion.SOUTHEAST_ASIA),
        Cuisine("malaysian", "Malaysian", "Malaysia", listOf("Malay", "Nyonya (Peranakan)", "Malaysian Indian", "Malaysian Chinese", "Kelantanese"), WorldRegion.SOUTHEAST_ASIA),
        Cuisine("indonesian", "Indonesian", "Indonesia", listOf("Javanese", "Padang (Minangkabau)", "Balinese", "Sundanese", "Manadonese"), WorldRegion.SOUTHEAST_ASIA),
        Cuisine("filipino", "Filipino", "Philippines", listOf("Tagalog", "Ilocano", "Kapampangan", "Visayan", "Bicolano"), WorldRegion.SOUTHEAST_ASIA),
        Cuisine("singaporean", "Singaporean", "Singapore", listOf("Hawker", "Peranakan"), WorldRegion.SOUTHEAST_ASIA),
        Cuisine("burmese", "Burmese", "Myanmar", listOf("Bamar", "Shan", "Mon", "Rakhine"), WorldRegion.SOUTHEAST_ASIA),
        Cuisine("cambodian", "Cambodian (Khmer)", "Cambodia", emptyList(), WorldRegion.SOUTHEAST_ASIA),
        Cuisine("lao", "Lao", "Laos", listOf("Luang Prabang", "Vientiane"), WorldRegion.SOUTHEAST_ASIA),
        Cuisine("mexican", "Mexican", "Mexico", listOf("Oaxacan", "Yucatecan", "Pueblan", "Northern (Norteño)", "Jaliscan", "Veracruzan"), WorldRegion.LATIN_AMERICA),
        Cuisine("peruvian", "Peruvian", "Peru", listOf("Coastal (Lima)", "Andean", "Amazonian", "Nikkei", "Chifa"), WorldRegion.LATIN_AMERICA),
        Cuisine("brazilian", "Brazilian", "Brazil", listOf("Bahian", "Mineiro", "Gaúcho", "Amazonian", "Paulista"), WorldRegion.LATIN_AMERICA),
        Cuisine("caribbean", "Caribbean", "Caribbean", listOf("Jamaican", "Trinidadian", "Haitian", "Puerto Rican", "Cuban", "Dominican", "Barbadian"), WorldRegion.LATIN_AMERICA),
        Cuisine("central_american", "Central American", "Central America", listOf("Guatemalan", "Salvadoran", "Honduran", "Nicaraguan", "Costa Rican", "Panamanian"), WorldRegion.LATIN_AMERICA),
        Cuisine("colombian", "Colombian", "Colombia", listOf("Antioquian (Paisa)", "Caribbean coast", "Andean (Bogotá)", "Valle del Cauca"), WorldRegion.LATIN_AMERICA),
        Cuisine("venezuelan", "Venezuelan", "Venezuela", listOf("Caracas", "Andean", "Zulian"), WorldRegion.LATIN_AMERICA),
        Cuisine("ecuadorian", "Ecuadorian", "Ecuador", listOf("Coastal", "Andean (Sierra)"), WorldRegion.LATIN_AMERICA),
        Cuisine("argentinian", "Argentinian", "Argentina", listOf("Pampas", "Northwest (Salta, Tucumán)", "Patagonian", "Porteño"), WorldRegion.LATIN_AMERICA),
        Cuisine("chilean", "Chilean", "Chile", listOf("Central Valley", "Chiloé", "Northern"), WorldRegion.LATIN_AMERICA),
        Cuisine("bolivian", "Bolivian", "Bolivia", listOf("Andean (La Paz)", "Cochabamba", "Lowland (Santa Cruz)"), WorldRegion.LATIN_AMERICA),
        Cuisine("italian", "Italian", "Italy", listOf("Sicilian", "Neapolitan / Campanian", "Roman / Lazio", "Emilian", "Tuscan", "Ligurian", "Venetian", "Piedmontese", "Apulian"), WorldRegion.EUROPE),
        Cuisine("greek", "Greek", "Greece", listOf("Cretan", "Macedonian", "Island (Cycladic)", "Peloponnesian"), WorldRegion.EUROPE),
        Cuisine("spanish", "Spanish", "Spain", listOf("Andalusian", "Basque", "Catalan", "Galician", "Valencian", "Castilian"), WorldRegion.EUROPE),
        Cuisine("french", "French", "France", listOf("Provençal", "Burgundian", "Lyonnaise", "Breton", "Alsatian", "Southwestern"), WorldRegion.EUROPE),
        Cuisine("portuguese", "Portuguese", "Portugal", listOf("Lisbon", "Porto / Minho", "Alentejo", "Algarve", "Azorean / Madeiran"), WorldRegion.EUROPE),
        Cuisine("dutch_belgian", "Dutch & Belgian", "Netherlands / Belgium", listOf("Dutch", "Flemish", "Walloon"), WorldRegion.EUROPE),
        Cuisine("nordic", "Nordic", "Scandinavia", listOf("Swedish", "Danish", "Norwegian", "Finnish", "Icelandic"), WorldRegion.EUROPE),
        Cuisine("balkan", "Balkan", "Balkans", listOf("Serbian", "Croatian", "Bosnian", "Bulgarian", "Romanian", "Albanian", "North Macedonian", "Slovenian"), WorldRegion.EUROPE),
        Cuisine("russian", "Russian", "Russia", listOf("Moscow", "Siberian", "Tatar", "Caucasus"), WorldRegion.EUROPE),
        Cuisine("british_irish", "British & Irish", "United Kingdom / Ireland", listOf("English", "Scottish", "Welsh", "Irish", "British-Asian"), WorldRegion.EUROPE),
        Cuisine("central_european", "Central & Eastern European", "Europe", listOf("German", "Polish", "Hungarian", "Ukrainian", "Czech", "Austrian", "Slovak", "Swiss", "Baltic"), WorldRegion.EUROPE),
        Cuisine("american", "American", "United States", listOf("Southern", "Tex-Mex", "Cajun / Creole", "New England", "Californian", "Soul food", "Midwestern", "Hawaiian", "Pacific Northwest"), WorldRegion.NORTH_AMERICA),
        Cuisine("canadian", "Canadian", "Canada", listOf("Québécois", "Atlantic", "Prairie", "Indigenous"), WorldRegion.NORTH_AMERICA),
        Cuisine("australian_nz", "Australian & New Zealand", "Australia / New Zealand", listOf("Australian", "New Zealand", "Māori"), WorldRegion.OCEANIA),
        Cuisine("pacific_islands", "Pacific Islands", "Pacific Islands", listOf("Fijian", "Samoan", "Tongan", "Polynesian"), WorldRegion.OCEANIA),
        Cuisine("international", "International", "—", emptyList(), WorldRegion.NORTH_AMERICA),
    )

    val byId: Map<String, Cuisine> = all.associateBy { it.id }

    operator fun get(id: String): Cuisine? = byId[id]

    /** Resolves a free-text cuisine mention ("Pakistani", "korean", "Persian") to an id. */
    fun resolve(text: String): Cuisine? {
        val t = text.trim().lowercase()
        if (t.isEmpty()) return null
        all.firstOrNull { it.id == t || it.name.lowercase() == t || it.country.lowercase() == t }?.let { return it }
        val synonyms = mapOf(
            "persian" to "iranian", "desi" to "pakistani", "south indian" to "indian", "north indian" to "indian",
            "cantonese" to "chinese", "sichuan" to "chinese", "szechuan" to "chinese", "levantine" to "lebanese",
            "middle eastern" to "lebanese", "arabic" to "lebanese", "tex-mex" to "american", "british" to "british_irish",
            "irish" to "british_irish", "english" to "british_irish", "jamaican" to "caribbean", "german" to "central_european",
            "polish" to "central_european", "mediterranean" to "greek", "punjabi" to "pakistani", "bengali" to "bangladeshi",
            "turkish" to "turkish", "west african" to "nigerian", "east african" to "kenyan", "japanese" to "japanese",
            "uzbek" to "central_asian", "kazakh" to "central_asian", "uyghur" to "central_asian", "saudi" to "gulf",
            "emirati" to "gulf", "khaleeji" to "gulf", "arabian" to "gulf", "swahili" to "tanzanian", "zanzibari" to "tanzanian",
            "cantonese dim sum" to "hong_kong", "khmer" to "cambodian", "laotian" to "lao", "myanmar" to "burmese",
            "swedish" to "nordic", "danish" to "nordic", "norwegian" to "nordic", "finnish" to "nordic", "scandinavian" to "nordic",
            "serbian" to "balkan", "croatian" to "balkan", "bosnian" to "balkan", "bulgarian" to "balkan", "romanian" to "balkan",
            "albanian" to "balkan", "dutch" to "dutch_belgian", "belgian" to "dutch_belgian", "hungarian" to "central_european",
            "ukrainian" to "central_european", "czech" to "central_european", "austrian" to "central_european", "swiss" to "central_european",
            "cuban" to "caribbean", "puerto rican" to "caribbean", "trinidadian" to "caribbean", "haitian" to "caribbean",
            "guatemalan" to "central_american", "salvadoran" to "central_american", "argentine" to "argentinian",
            "australian" to "australian_nz", "new zealand" to "australian_nz", "hawaiian" to "american",
            "cajun" to "american", "creole" to "american", "southern" to "american", "cape malay" to "south_african",
        )
        synonyms[t]?.let { return byId[it] }
        return all.firstOrNull { c -> t.contains(c.name.lowercase()) || t.contains(c.id) || c.regions.any { it.lowercase() == t } }
    }
}
