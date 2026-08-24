package com.ramajo.logs.system.services;

import com.ramajo.logs.system.entities.Log;
import com.ramajo.logs.system.repositories.LogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class LogService {
    private final LogRepository LogRepo;

    public Log cadastrar(Log log){

        return LogRepo.save(log);
    }
}
