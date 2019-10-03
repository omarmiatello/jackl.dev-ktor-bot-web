package com.github.jacklt.gae.ktor.tg

import com.github.jacklt.gae.ktor.tg.appengine.appEngineCacheFast
import com.github.jacklt.gae.ktor.tg.appengine.jsoupGet
import com.github.jacklt.gae.ktor.tg.appengine.telegram.Message
import com.github.jacklt.gae.ktor.tg.data.FireDB
import kotlinx.serialization.Serializable
import kotlinx.serialization.map
import kotlinx.serialization.serializer
import org.jsoup.select.Elements
import java.text.NumberFormat
import java.util.*
import java.util.logging.Level
import java.util.logging.Logger

fun main() = startApp()

private val immUrlRegex = "(https://www\\.immobiliare\\.it/annunci/\\d+)/?.*?".toRegex()
private val ideUrlRegex = "(https://www\\.idealista\\.it/immobile/\\d+)/?.*?".toRegex()
private val immAmountRegex = ".*?€ ([\\d.]+).*".toRegex()
private val ideAmountRegex = ".*?([\\d.]+) €.*".toRegex()

private val NAME_REVIEW_SERIALIZER = (String.serializer() to Review.serializer()).map

private fun show(prefix: String, field: String?) = if (field.isNullOrEmpty()) "" else "\n|$prefix$field"

private fun Elements.toIntOrDefault(default: Int) =
    firstOrNull()?.let { it.text().filter { it.isDigit() }.toIntOrNull() } ?: default

private val Message.userName
    get() = from?.run { username ?: listOfNotNull(first_name, last_name).joinToString(" ") } ?: "unknown user"

private fun List<Pair<House, Map<String, Review>>>.ordered(): List<Pair<House, Map<String, Review>>> {
    return sortedByDescending { it.first.price }
        .sortedByDescending { it.second.map { it.value.vote }.ifEmpty { listOf(0) }.average() }
        .sortedBy { it.first.action }
}

private fun getHousesWithReviews(): List<Pair<House, Map<String, Review>>> {
    val houses = FireDB["house", (String.serializer() to House.serializer()).map].orEmpty().values
    val reviewsMap = FireDB["review", (String.serializer() to NAME_REVIEW_SERIALIZER).map].orEmpty()
    val toList = houses.associateWith { reviewsMap["${it.site}_${it.id}"].orEmpty() }.toList()
    return toList
}

fun myApp(message: Message): String {
    val userInput = message.text.orEmpty()
    val houseId = appEngineCacheFast[message.userName]
    appEngineCacheFast.delete(message.userName)
    return when {
        userInput.toLowerCase() in listOf("casa", "case", "buy", "compra", "🏠", "🏡", "🏘", "🏚") -> {
            showHouses(getHousesWithReviews().filter { it.first.action == House.ACTION_BUY }.ordered())
        }
        userInput.toLowerCase() in listOf("affitto", "affitta", "rent", "🛏") -> {
            showHouses(getHousesWithReviews().filter { it.first.action == House.ACTION_RENT }.ordered())
        }
        userInput.toLowerCase() in listOf("asta", "auction", "bid", "📢") -> {
            showHouses(getHousesWithReviews().filter { it.first.action == House.ACTION_AUCTION }.ordered())
        }
        userInput.startsWith("/") -> showHouse(message, userInput.drop(1).takeWhile { it != '@' })
        houseId != null && userInput.firstOrNull()?.isDigit() ?: false -> updateComment(message, houseId)
        houseId != null && userInput.toLowerCase() == "delete" -> deleteHouse(houseId)
        else -> searchAndSaveHouses(message)
    }
}

private fun showHouse(message: Message, houseId: String): String {
    val house = FireDB["house/$houseId", House.serializer()]
    val reviewsMap = FireDB["review/$houseId", NAME_REVIEW_SERIALIZER].orEmpty()
    return if (house != null) {
        appEngineCacheFast[message.userName] = houseId
        val reviews = reviewsMap.toList().joinToString("\n") { "${it.first} ${it.second}" }
        """${house.descDetails(showUrl = true)}
            |-- REVIEWS --${show("", reviews)}
            |
            |Puoi lasciare un voto (1-10) e se vuoi a fianco un piccolo commento, es: "10 bellissima!"
            |Per eliminare la casa (e i commenti) scrivi: delete
    """.trimMargin()
    } else {
        "Non c'è la casa $houseId"
    }
}

private fun updateComment(message: Message, houseId: String): String {
    val house = FireDB["house/$houseId", House.serializer()]
    val userInput = message.text.orEmpty()
    val review = Review(userInput.takeWhile { it.isDigit() }.toInt(), userInput.dropWhile { it.isDigit() }.drop(1))
    FireDB["review/$houseId/${message.userName}", Review.serializer()] = review
    return if (house != null) {
        "${house.descShort()}\n${message.userName} $review"
    } else {
        "Non c'è la casa $houseId"
    }
}

private fun showHouses(houseReviews: List<Pair<House, Map<String, Review>>>): String {
    return houseReviews.joinToString("\n\n") {
        val reviews = it.second.toList().joinToString("\n") { "${it.first} ${it.second}" }
        "${it.first.descShort(showTags = false)}${show("", reviews)}".trimMargin()
    }
}

private fun deleteHouse(houseId: String): String {
    FireDB.delete("house/$houseId")
    FireDB.delete("review/$houseId")
    return "🏠 eliminata!"
}

private fun searchAndSaveHouses(message: Message): String {
    val userInput = message.text.orEmpty()
    val urls = message.entities
        ?.filter { it.type == "url" }
        ?.map { userInput.substring(it.offset, it.offset + it.length) }
        .orEmpty()

    val errors = mutableListOf<String>()
    val houses = urls.mapNotNull {
        try {
            it.parseHouse()
        } catch (e: Exception) {
            Logger.getLogger("ok").log(Level.WARNING, e.message, e)
            errors += e.message.orEmpty()
            null
        }
    }

    FireDB.update("house", houses.associateBy { "${it.site}_${it.id}" }, House.serializer())

    return if (houses.size == 1) {
        appEngineCacheFast[message.userName] = houses.first().let { "${it.site}_${it.id}" }
        """${houses.first().descDetails()}
            |
            |Puoi lasciare un voto (1-10) e se vuoi a fianco un piccolo commento, es: "10 bellissima!"
        """.trimMargin()
    } else {
        val housesDesc = show("\n", houses.joinToString("\n\n") { it.descShort() })
        val errorMsg = if (errors.size == 0) "" else " (${errors.size} errori)"
        """Trovate ${houses.size} case$errorMsg.$housesDesc""".trimMargin()
    }
}

private fun String.parseHouse(): House {
    return when {
        immUrlRegex.matches(this) -> parseImmobiliare(immUrlRegex.find(this)!!.groupValues[1])
        ideUrlRegex.matches(this) -> parseIdealista(ideUrlRegex.find(this)!!.groupValues[1])
        else -> error("Unknown url $this")
    }
}

private fun parseImmobiliare(url: String): House {
    val html = try {
        jsoupGet(url)
    } catch (e: Exception) {
        error("Immobiliare: $url non disponibile, ${e.javaClass.simpleName}")
    }

    val detailsMap = html.select(".section-data .col-xs-12 .col-xs-12").map { it.text() }
        .chunked(2).map { it[0] to it[1] }.toMap()
    val priceText = html.select(".features__price > span").first().text()
    val price = immAmountRegex.find(priceText)!!.groupValues[1].filter { it.isDigit() }.toInt()

    val contract = detailsMap["Contratto"].orEmpty()

    return House(
        site = "immobiliare",
        id = url.takeLastWhile { it.isDigit() },
        action = when {
            contract.contains("Vendita", ignoreCase = true) -> House.ACTION_BUY
            contract.contains("Affitto", ignoreCase = true) -> House.ACTION_RENT
            else -> House.ACTION_AUCTION
        },
        url = url,
        price = price,
        title = html.getElementsByClass("title-detail").text(),
        subtitle = html.getElementsByClass("description__title").text(),
        description = html.getElementsByClass("description-text").text(),
        details = detailsMap,
        tags = html.select(".label-gray").map { it.text() }.joinToString(),
        planimetry_photos = html.select("#plan-tab").toIntOrDefault(0),
        video = html.select("#video-tab").toIntOrDefault(0),
        photos = html.select("#foto-tab").toIntOrDefault(0),
        address = html.select(".leaflet-control").firstOrNull()?.text()
    )
}

private fun parseIdealista(url: String): House {
    val html = try {
        jsoupGet(url)
    } catch (e: Exception) {
        error("Idealista: $url non disponibile, ${e.javaClass.simpleName}")
    }

    val detailsList = html.select("#details li, .flex-feature .txt-medium").map { it.text() }
    val priceText = html.select(".features__price > span , .info-data-price").first().text()
    val price = ideAmountRegex.find(priceText)!!.groupValues[1].filter { it.isDigit() }.toInt()

    return House(
        site = "idealista",
        id = url.takeLastWhile { it.isDigit() },
        action = if (price > 5000) House.ACTION_BUY else House.ACTION_RENT,
        url = url,
        price = price,
        title = html.getElementsByClass("main-info__title-main").text(),
        subtitle = html.getElementsByClass("main-info__title-minor").text(),
        description = html.getElementsByClass("comment").text(),
        details = detailsList.mapIndexed { index, s -> "d$index" to s }.toMap(),
        tags = null,
        planimetry_photos = html.getElementsContainingText("Planimetria").size,
        video = 0,
        photos = html.select(".show").size + html.select(".more-photos").toIntOrDefault(0),
        address = html.select("#headerMap li").text()
    )
}

@Serializable
data class House(
    val site: String,
    val id: String,
    val action: String,
    val url: String,
    val price: Int,
    val title: String,
    val subtitle: String,
    val description: String,
    val details: Map<String, String>,
    val tags: String? = null,
    val planimetry_photos: Int,
    val video: Int,
    val photos: Int,
    val address: String? = null
) {
    val priceFormatted get() = NumberFormat.getCurrencyInstance(Locale.ITALY).format(price)

    val actionIcon
        get() = when (action) {
            ACTION_AUCTION -> "📢"
            ACTION_BUY -> "🏠"
            ACTION_RENT -> "🛏"
            else -> "❓"
        }

    fun descShort(showTags: Boolean = true) =
        """$actionIcon $title
        |$priceFormatted /${site}_$id${show("", tags.takeIf { showTags })}
    """.trimMargin()

    fun descDetails(showUrl: Boolean = false) =
        """$actionIcon $title, $subtitle
        |Price: $priceFormatted
        |Details: ${details.orEmpty().toList().joinToString("\n") {
            if (it.first.drop(1).all { it.isDigit() }) {
                it.second
            } else {
                it.first + ": " + it.second
            }
        }}
        |/${site}_$id
        |Images: $photos 📷, $video 📹, $planimetry_photos 🗺${show("Tags: ", tags)}${show("Url: ", url)}
    """.trimMargin()

    companion object {
        const val ACTION_AUCTION = "auction"
        const val ACTION_BUY = "buy"
        const val ACTION_RENT = "rent"
    }
}

@Serializable
data class Review(val vote: Int, val commment: String?) {
    override fun toString() = "Vote: $vote - $commment"
}