package com.Brafurries.API.repository.misc;

import com.Brafurries.API.entity.misc.Command;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CommandRepository extends JpaRepository<Command, Integer> {
}
