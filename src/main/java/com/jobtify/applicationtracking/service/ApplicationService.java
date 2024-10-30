package com.jobtify.applicationtracking.service;

import com.jobtify.applicationtracking.model.Application;
import com.jobtify.applicationtracking.repository.ApplicationRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.beans.factory.annotation.Value;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * @author Ziyang Su
 * @version 1.0.0
 */

@Service
public class ApplicationService {

    private final ApplicationRepository applicationRepository;
    private final RestTemplate restTemplate;
    private final WebClient.Builder webClientBuilder;

    @Value("${user.service.url}")
    private String userServiceUrl;

    @Value("${job.service.url}")
    private String jobServiceUrl;

    // Insert by Constructor
    public ApplicationService(ApplicationRepository applicationRepository, RestTemplate restTemplate, WebClient.Builder webClientBuilder) {
        this.applicationRepository = applicationRepository;
        this.restTemplate = restTemplate;
        this.webClientBuilder = webClientBuilder;
    }

    // POST: Create new application
    public Application createApplication(Long userId, Long jobId, Application application) {
        application.setUserId(userId);
        application.setJobId(jobId);
        Application createdApplication = applicationRepository.save(application);

        incrementJobApplicantCountAsync(jobId).thenAccept(statusCode -> {
            if (statusCode == 202) {
                System.out.println("Applicant count increment accepted for Job ID: " + jobId);
            } else if (statusCode == 404) {
                System.out.println("No job found with ID: " + jobId);
            } else if (statusCode == 500) {
                System.out.println("Unexpected server error when updating Job ID: " + jobId);
            }
        }).exceptionally(ex -> {
            System.err.println("Failed to increment job applicant count for jobId: " + jobId + ". Error: " + ex.getMessage());
            return null;
        });

        return createdApplication;
    }

    // PUT: update application
    public Application updateApplication(Long applicationId, String status, String notes, LocalDateTime timeOfApplication) {
        Application application = applicationRepository.findById(applicationId)
                .orElseThrow(() -> new RuntimeException("Application not found"));
        if (status != null) application.setApplicationStatus(status);
        if (notes != null) application.setNotes(notes);
        if (timeOfApplication != null) application.setTimeOfApplication(timeOfApplication);
        return applicationRepository.save(application);
    }

    // GET: get all application by user_id
    public List<Application> getApplicationsByUserId(Long userId, String status) {
        if (status != null) {
            return applicationRepository.findByUserIdAndApplicationStatus(userId, status);
        }
        return applicationRepository.findByUserId(userId);
    }

    // GET: get all application by job_id
    public List<Application> getApplicationsByJobId(Long jobId, String status) {
        if (status != null) {
            return applicationRepository.findByJobIdAndApplicationStatus(jobId, status);
        }
        return applicationRepository.findByJobId(jobId);
    }

    // Delete an application
    public void deleteApplication(Long applicationId) {
        if (!applicationRepository.existsById(applicationId)) {
            throw new RuntimeException("Application not found");
        }
        applicationRepository.deleteById(applicationId);
    }

    public boolean validateUser(Long userId) {
        String userUrl = userServiceUrl + "/" + userId + "/exists";
        try {
            Boolean userExists = restTemplate.getForObject(userUrl, Boolean.class);
            return userExists != null && userExists;
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode() == HttpStatus.NOT_FOUND) {
                return false;
            } else if (e.getStatusCode() == HttpStatus.INTERNAL_SERVER_ERROR) {
                throw new RuntimeException("User service returned 500 error");
            }
            throw new RuntimeException("Error validating User ID: " + userId, e);
        }
    }

    public boolean validateJob(Long jobId) {
        String jobUrl = jobServiceUrl + "/" + jobId;
        try {
            restTemplate.getForObject(jobUrl, Object.class);
            return true;
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode() == HttpStatus.NOT_FOUND) {
                return false;
            } else if (e.getStatusCode() == HttpStatus.INTERNAL_SERVER_ERROR) {
                throw new RuntimeException("Job service returned 500 error");
            }
            throw new RuntimeException("Error validating Job ID: " + jobId, e);
        }
    }

    public CompletableFuture<Integer> incrementJobApplicantCountAsync(Long jobId) {
        String jobUrl = "http://54.90.234.55:8080/api/jobs/async/update/" + jobId;

        return webClientBuilder.build()
                .post()
                .uri(jobUrl)
                .retrieve()
                .toBodilessEntity()
                .map(response -> response.getStatusCode().value())
                .doOnError(ex -> System.err.println("Error incrementing applicant count for Job ID: " + jobId + ". Error: " + ex.getMessage()))
                .toFuture();
    }
}
