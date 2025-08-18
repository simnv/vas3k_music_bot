package dev.storozhenko.music

import com.fasterxml.jackson.annotation.JsonProperty
import org.telegram.telegrambots.meta.api.objects.MessageEntity

data class OdesilResponse(
    @JsonProperty("entityUniqueId") val entityUniqueId: String,
    @JsonProperty("linksByPlatform") val linksByPlatform: Map<String, OdesilPlatformData>,
    @JsonProperty("entitiesByUniqueId") val entitiesByUniqueId: Map<String, OdesilEntityData>
)

data class OdesilPlatformData(
    @JsonProperty("url") val url: String
)

data class OdesilEntityData(
    @JsonProperty("title") val title: String?,
    @JsonProperty("artistName") val artistName: String?
)

class OdesilEntity(
    val odesilResponse: OdesilResponse,
    val messageEntity: MessageEntity
)
