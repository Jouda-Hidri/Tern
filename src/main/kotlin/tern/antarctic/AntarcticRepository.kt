package tern.antarctic

import org.springframework.data.jdbc.repository.query.Query
import org.springframework.data.repository.CrudRepository

interface MessageRepository : CrudRepository<Message, String> {
    @Query("SELECT * FROM messages")
    fun findMessages(): List<Message>
}