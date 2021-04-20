package com.info7255.demo.controller;

import com.info7255.demo.DemoApplication;
import com.info7255.demo.exception.*;
import com.info7255.demo.model.ErrorResponse;
import com.info7255.demo.model.JwtResponse;
import com.info7255.demo.service.ETagService;
import com.info7255.demo.service.PlanService;
import com.info7255.demo.util.JwtUtil;
import org.everit.json.schema.Schema;
import org.everit.json.schema.ValidationException;
import org.everit.json.schema.loader.SchemaLoader;
import org.json.JSONObject;
import org.json.JSONTokener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
public class PlanController {
    private PlanService planService;
    private final JwtUtil jwtUtil;
    private final RabbitTemplate template;

    public PlanController(PlanService planService, JwtUtil jwtUtil, RabbitTemplate template) {
        this.planService = planService;
        this.jwtUtil = jwtUtil;
        this.template = template;
    }

    @GetMapping("/token")
    public ResponseEntity<JwtResponse> generateToken() {
        String token = jwtUtil.generateToken();
        return new ResponseEntity<>(new JwtResponse(token), HttpStatus.CREATED);
    }

    @PostMapping("/validate")
    public boolean validateToken(@RequestHeader HttpHeaders requestHeader) {
        boolean result;
        String authorization = requestHeader.getFirst("Authorization");
        if (authorization == null || authorization.isBlank()) throw new UnauthorizedException("Missing token!");
        try {
            String token = authorization.split(" ")[1];
            result = jwtUtil.validateToken(token);
        } catch (Exception e) {
            throw new UnauthorizedException("Invalid Token");
        }
        return result;
    }

    @PostMapping(value = "/plan", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> createPlan(@RequestBody(required = false) String planObject) {
        if (planObject == null || planObject.isBlank()) throw new BadRequestException("Request body is missing!");

        JSONObject plan = new JSONObject(planObject);
        JSONObject schemaJSON = new JSONObject(new JSONTokener(Objects.requireNonNull(PlanController.class.getResourceAsStream("/plan-schema.json"))));
        Schema schema = SchemaLoader.load(schemaJSON);
        try {
            schema.validate(plan);
        } catch (ValidationException e) {
            throw new BadRequestException(e.getMessage());
        }

        String key = "plan:" + plan.getString("objectId");
        if (planService.isKeyPresent(key)) throw new ConflictException("Plan already exists!");

        String eTag = planService.createPlan(plan, key);

        // Send a message to queue for indexing
        Map<String, String> message = new HashMap<>();
        message.put("operation", "SAVE");
        message.put("body", planObject);

        System.out.println("Sending message: " + message);
        template.convertAndSend(DemoApplication.queueName, message);

        HttpHeaders headersToSend = new HttpHeaders();
        headersToSend.setETag(eTag);

        return new ResponseEntity<>("{\"objectId\": \"" + plan.getString("objectId") + "\"}", headersToSend, HttpStatus.CREATED);
    }

    @GetMapping(value = "/{objectType}/{objectId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> getPlan(@PathVariable String objectId,
                                     @PathVariable String objectType,
                                     @RequestHeader HttpHeaders headers) {
        String key = objectType + ":" + objectId;
        if (!planService.isKeyPresent(key)) throw new ResourceNotFoundException("Object not found!");

        // Check if the ETag provided is not corrupt
        List<String> ifNoneMatch;
        try {
            ifNoneMatch = headers.getIfNoneMatch();
        } catch (Exception e) {
            throw new ETagParseException("ETag value invalid! Make sure the ETag value is a string!");
        }

        String eTag = planService.getETag(key);
        ;
        HttpHeaders headersToSend = new HttpHeaders();
        headersToSend.setETag(eTag);


        if (objectType.equals("plan") && ifNoneMatch.contains(eTag))
            return new ResponseEntity<>(null, headersToSend, HttpStatus.NOT_MODIFIED);

        Map<String, Object> objectToReturn = planService.getPlan(key);

        if (objectType.equals("plan"))
            return new ResponseEntity<>(objectToReturn, headersToSend, HttpStatus.OK);

        return new ResponseEntity<>(objectToReturn, HttpStatus.OK);
    }

    @DeleteMapping("/{objectType}/{objectId}")
    public ResponseEntity<?> deletePlan(@PathVariable String objectId,
                                        @PathVariable String objectType,
                                        @RequestHeader HttpHeaders headers) {
        String key = objectType + ":" + objectId;
        if (!planService.isKeyPresent(key)) throw new ResourceNotFoundException("Plan not found!");

        String eTag = planService.getETag(key);
        List<String> ifMatch;
        try {
            ifMatch = headers.getIfMatch();
        } catch (Exception e) {
            throw new ETagParseException("ETag value invalid! Make sure the ETag value is a string!");
        }

        if (ifMatch.size() == 0) throw new ETagParseException("ETag is not provided with request!");
        if (!ifMatch.contains(eTag)) return preConditionFailed(eTag);

        // Send message to queue for deleting indices
        Map<String, Object> plan = planService.getPlan(key);
        Map<String, String> message = new HashMap<>();
        message.put("operation", "DELETE");
        message.put("body",  new JSONObject(plan).toString());

        System.out.println("Sending message: " + message);
        template.convertAndSend(DemoApplication.queueName, message);

        planService.deletePlan(key);
        return new ResponseEntity<>(HttpStatus.NO_CONTENT);
    }

    @PutMapping(value = "/plan/{objectId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> updatePlan(@PathVariable String objectId,
                                        @RequestBody(required = false) String planObject,
                                        @RequestHeader HttpHeaders headers) {
        if (planObject == null || planObject.isBlank()) throw new BadRequestException("Request body is missing!");

        JSONObject plan = new JSONObject(planObject);
        String key = "plan:" + objectId;
        if (!planService.isKeyPresent(key)) throw new ResourceNotFoundException("Plan not found!");

        String eTag = planService.getETag(key);
        List<String> ifMatch;
        try {
            ifMatch = headers.getIfMatch();
        } catch (Exception e) {
            throw new ETagParseException("ETag value invalid! Make sure the ETag value is a string!");
        }

        if (ifMatch.size() == 0) throw new ETagParseException("ETag is not provided with request!");
        if (!ifMatch.contains(eTag)) return preConditionFailed(eTag);

        JSONObject schemaJSON = new JSONObject(new JSONTokener(Objects.requireNonNull(PlanController.class.getResourceAsStream("/plan-schema.json"))));
        Schema schema = SchemaLoader.load(schemaJSON);
        try {
            schema.validate(plan);
        } catch (ValidationException e) {
            throw new BadRequestException(e.getMessage());
        }
        // Send message to queue for deleting previous indices incase of put
        Map<String, Object> oldPlan = planService.getPlan(key);
        Map<String, String> message = new HashMap<>();
        message.put("operation", "DELETE");
        message.put("body", new JSONObject(oldPlan).toString());

        System.out.println("Sending message: " + message);
        template.convertAndSend(DemoApplication.queueName, message);

        planService.deletePlan(key);
        String updatedETag = planService.createPlan(plan, key);

        // Send message to queue for index update
        Map<String, Object> newPlan = planService.getPlan(key);
        message = new HashMap<>();
        message.put("operation", "SAVE");
        message.put("body", new JSONObject(newPlan).toString());

        System.out.println("Sending message: " + message);
        template.convertAndSend(DemoApplication.queueName, message);

        HttpHeaders headersToSend = new HttpHeaders();
        headersToSend.setETag(updatedETag);
        return new ResponseEntity<>("{\"message\": \"Plan updated successfully\"}",
                headersToSend,
                HttpStatus.OK);
    }

    @PatchMapping(value = "/{objectType}/{objectId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> patchPlan(@PathVariable String objectId,
                                       @RequestBody(required = false) String planObject,
                                       @RequestHeader HttpHeaders headers) {
        if (planObject == null || planObject.isBlank()) throw new BadRequestException("Request body is missing!");

        JSONObject plan = new JSONObject(planObject);
        String key = "plan:" + objectId;
        if (!planService.isKeyPresent(key)) throw new ResourceNotFoundException("Plan not found!");

        String eTag = planService.getETag(key);
        List<String> ifMatch;
        try {
            ifMatch = headers.getIfMatch();
        } catch (Exception e) {
            throw new ETagParseException("ETag value invalid! Make sure the ETag value is a string!");
        }

        if (ifMatch.size() == 0) throw new ETagParseException("ETag is not provided with request!");
        if (!ifMatch.contains(eTag)) return preConditionFailed(eTag);

        String updatedEtag = planService.createPlan(plan, key);

        // Send message to queue for index update
        Map<String, String> message = new HashMap<>();
        message.put("operation", "SAVE");
        message.put("body", planObject);

        System.out.println("Sending message: " + message);
        template.convertAndSend(DemoApplication.queueName, message);

        return ResponseEntity.ok()
                .eTag(updatedEtag)
                .body(new JSONObject().put("message: ", "Plan updated successfully!!").toString());
    }

    private ResponseEntity preConditionFailed(String eTag) {
        HttpHeaders headersToSend = new HttpHeaders();
        headersToSend.setETag(eTag);
        ErrorResponse errorResponse = new ErrorResponse(
                "Plan has been updated",
                HttpStatus.PRECONDITION_FAILED.value(),
                new Date(),
                HttpStatus.PRECONDITION_REQUIRED.getReasonPhrase()
        );
        return new ResponseEntity<>(errorResponse, headersToSend, HttpStatus.PRECONDITION_FAILED);
    }
}
