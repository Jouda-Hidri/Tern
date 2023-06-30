package tern.antarctic

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table

@Table("messages")
data class Message(@Id val id: String?, val text: String)