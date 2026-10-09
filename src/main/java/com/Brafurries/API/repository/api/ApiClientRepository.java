package com.Brafurries.API.repository.api;

import com.Brafurries.API.entity.api.ApiClient;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ApiClientRepository extends JpaRepository<ApiClient, Integer> {
}
