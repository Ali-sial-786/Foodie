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
        Cuisine("afghan", "Afghan", "Afghanistan", listOf("Kabuli", "Herati", "Kandahari", "Uzbek-Afghan"), WorldRegion.CENTRAL_WEST_ASIA),
        Cuisine("iranian", "Iranian (Persian)", "Iran", listOf("Tehrani", "Gilani (Caspian)", "Azerbaijani-Iranian", "Shirazi", "Khorasani"), WorldRegion.MIDDLE_EAST),
        Cuisine("turkish", "Turkish", "Türkiye", listOf("Anatolian", "Aegean", "Black Sea", "Southeastern (Gaziantep, Urfa)", "Ottoman / Istanbul"), WorldRegion.MIDDLE_EAST),
        Cuisine("lebanese", "Lebanese", "Lebanon", listOf("Beiruti", "Mountain", "Tripoli"), WorldRegion.MIDDLE_EAST),
        Cuisine("syrian", "Syrian", "Syria", listOf("Aleppine", "Damascene"), WorldRegion.MIDDLE_EAST),
        Cuisine("palestinian", "Palestinian", "Palestine", listOf("Galilean", "Gazan", "West Bank (Nablus, Hebron)"), WorldRegion.MIDDLE_EAST),
        Cuisine("egyptian", "Egyptian", "Egypt", listOf("Cairene", "Alexandrian", "Upper Egyptian (Saidi)"), WorldRegion.NORTH_AFRICA),
        Cuisine("moroccan", "Moroccan", "Morocco", listOf("Fassi", "Marrakchi", "Berber / Amazigh", "Tangier"), WorldRegion.NORTH_AFRICA),
        Cuisine("ethiopian", "Ethiopian", "Ethiopia", listOf("Amhara", "Tigrayan", "Oromo", "Gurage"), WorldRegion.SUB_SAHARAN_AFRICA),
        Cuisine("nigerian", "Nigerian", "Nigeria", listOf("Yoruba", "Igbo", "Hausa", "Efik / Calabar"), WorldRegion.SUB_SAHARAN_AFRICA),
        Cuisine("ghanaian", "Ghanaian", "Ghana", listOf("Akan / Ashanti", "Ewe", "Ga", "Northern"), WorldRegion.SUB_SAHARAN_AFRICA),
        Cuisine("kenyan", "Kenyan", "Kenya", listOf("Swahili coast", "Kikuyu", "Luo"), WorldRegion.SUB_SAHARAN_AFRICA),
        Cuisine("chinese", "Chinese", "China",
            listOf("Sichuan", "Cantonese", "Hunan", "Shandong", "Jiangsu (Huaiyang)", "Zhejiang", "Fujian", "Anhui", "Xinjiang / Uyghur", "Dongbei", "Shanghainese", "Hakka"),
            WorldRegion.EAST_ASIA),
        Cuisine("japanese", "Japanese", "Japan", listOf("Kanto", "Kansai", "Hokkaido", "Okinawan", "Kyushu"), WorldRegion.EAST_ASIA),
        Cuisine("korean", "Korean", "Korea", listOf("Seoul", "Jeolla", "Gyeongsang", "Jeju", "Temple (sachal)"), WorldRegion.EAST_ASIA),
        Cuisine("thai", "Thai", "Thailand", listOf("Central", "Northern (Lanna)", "Northeastern (Isan)", "Southern"), WorldRegion.SOUTHEAST_ASIA),
        Cuisine("vietnamese", "Vietnamese", "Vietnam", listOf("Northern (Hanoi)", "Central (Hue)", "Southern (Saigon)"), WorldRegion.SOUTHEAST_ASIA),
        Cuisine("malaysian", "Malaysian", "Malaysia", listOf("Malay", "Nyonya (Peranakan)", "Malaysian Indian", "Malaysian Chinese", "Kelantanese"), WorldRegion.SOUTHEAST_ASIA),
        Cuisine("indonesian", "Indonesian", "Indonesia", listOf("Javanese", "Padang (Minangkabau)", "Balinese", "Sundanese", "Manadonese"), WorldRegion.SOUTHEAST_ASIA),
        Cuisine("filipino", "Filipino", "Philippines", listOf("Tagalog", "Ilocano", "Kapampangan", "Visayan", "Bicolano"), WorldRegion.SOUTHEAST_ASIA),
        Cuisine("mexican", "Mexican", "Mexico", listOf("Oaxacan", "Yucatecan", "Pueblan", "Northern (Norteño)", "Jaliscan", "Veracruzan"), WorldRegion.LATIN_AMERICA),
        Cuisine("peruvian", "Peruvian", "Peru", listOf("Coastal (Lima)", "Andean", "Amazonian", "Nikkei", "Chifa"), WorldRegion.LATIN_AMERICA),
        Cuisine("brazilian", "Brazilian", "Brazil", listOf("Bahian", "Mineiro", "Gaúcho", "Amazonian", "Paulista"), WorldRegion.LATIN_AMERICA),
        Cuisine("caribbean", "Caribbean", "Caribbean", listOf("Jamaican", "Trinidadian", "Haitian", "Puerto Rican", "Cuban", "Dominican", "Barbadian"), WorldRegion.LATIN_AMERICA),
        Cuisine("italian", "Italian", "Italy", listOf("Sicilian", "Neapolitan / Campanian", "Roman / Lazio", "Emilian", "Tuscan", "Ligurian", "Venetian", "Piedmontese", "Apulian"), WorldRegion.EUROPE),
        Cuisine("greek", "Greek", "Greece", listOf("Cretan", "Macedonian", "Island (Cycladic)", "Peloponnesian"), WorldRegion.EUROPE),
        Cuisine("spanish", "Spanish", "Spain", listOf("Andalusian", "Basque", "Catalan", "Galician", "Valencian", "Castilian"), WorldRegion.EUROPE),
        Cuisine("french", "French", "France", listOf("Provençal", "Burgundian", "Lyonnaise", "Breton", "Alsatian", "Southwestern"), WorldRegion.EUROPE),
        Cuisine("british_irish", "British & Irish", "United Kingdom / Ireland", listOf("English", "Scottish", "Welsh", "Irish", "British-Asian"), WorldRegion.EUROPE),
        Cuisine("central_european", "Central & Eastern European", "Europe", listOf("German", "Polish", "Hungarian", "Ukrainian", "Czech", "Austrian"), WorldRegion.EUROPE),
        Cuisine("american", "American", "United States", listOf("Southern", "Tex-Mex", "Cajun / Creole", "New England", "Californian", "Soul food", "Midwestern"), WorldRegion.NORTH_AMERICA),
        Cuisine("canadian", "Canadian", "Canada", listOf("Québécois", "Atlantic", "Prairie", "Indigenous"), WorldRegion.NORTH_AMERICA),
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
        )
        synonyms[t]?.let { return byId[it] }
        return all.firstOrNull { c -> t.contains(c.name.lowercase()) || t.contains(c.id) || c.regions.any { it.lowercase() == t } }
    }
}
