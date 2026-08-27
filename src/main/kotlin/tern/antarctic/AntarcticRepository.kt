package tern.antarctic

import org.springframework.data.jdbc.repository.query.Query
import org.springframework.data.repository.CrudRepository

interface MessageRepository : CrudRepository<MessageEntity, String> {
    @Query("SELECT * FROM messages")
    fun findMessages(): List<MessageEntity>
}
