package jp.mobgits.lab.feature.inbox

import java.net.URI
import java.net.URLEncoder
import java.net.URLDecoder
import jp.mobgits.lab.feature.collaboration.Destination

object DeepLinks {
    private val kinds = setOf("issue", "mr", "pipeline", "job")
    fun create(account: String, target: Destination): String {
        require(target.kind in kinds && target.project > 0 && target.id > 0)
        return "mobgits://open?account=${URLEncoder.encode(account, "UTF-8")}&kind=${target.kind}&project=${target.project}&id=${target.id}"
    }
    fun parse(value: String): Pair<String, Destination>? = runCatching {
        val uri = URI(value)
        require(uri.scheme == "mobgits" && uri.rawAuthority == "open" && uri.rawPath.orEmpty().isEmpty() && uri.rawFragment == null)
        val pairs = uri.rawQuery.orEmpty().split('&').map { it.split('=', limit = 2).let { p -> URLDecoder.decode(p[0], "UTF-8") to URLDecoder.decode(p[1], "UTF-8") } }
        require(pairs.distinctBy { it.first }.size == pairs.size)
        val p = pairs.toMap(); val account = p.getValue("account"); require(account.isNotBlank() && account.length <= 100)
        val d = Destination(p.getValue("kind"), p.getValue("project").toLong(), p.getValue("id").toLong())
        require(d.kind in kinds && d.project > 0 && d.id > 0)
        account to d
    }.getOrNull()
}
