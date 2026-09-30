package com.example.domain

trait DomainError:
  def code: String
  def message: String
