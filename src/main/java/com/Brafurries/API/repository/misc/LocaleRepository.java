package com.Brafurries.API.repository.misc;

import com.Brafurries.API.entity.misc.Locale;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface LocaleRepository extends JpaRepository<Locale, Integer> {
}
