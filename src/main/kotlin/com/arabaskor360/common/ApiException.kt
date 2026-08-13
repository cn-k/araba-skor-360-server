package com.arabaskor360.common

class ApiException(val status: Int, message: String) : RuntimeException(message)

class NotFoundException(message: String = "Not found") : RuntimeException(message)

class BadRequestException(message: String) : RuntimeException(message)
