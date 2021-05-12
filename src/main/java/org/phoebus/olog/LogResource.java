/*
 * Copyright (c) 2010-2020 Brookhaven National Laboratory
 * Copyright (c) 2010-2020 Helmholtz-Zentrum Berlin für Materialien und Energie GmbH
 * All rights reserved. Use is subject to license terms and conditions.
 */
package org.phoebus.olog;

import static org.phoebus.olog.OlogResourceDescriptors.LOG_RESOURCE_URI;
import static org.phoebus.util.time.TimestampFormats.MILLI_FORMAT;

import java.io.IOException;
import java.security.Principal;
import java.time.Instant;
import java.time.temporal.TemporalAmount;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import javax.validation.Valid;

import org.apache.commons.collections4.CollectionUtils;
import org.phoebus.olog.entity.Attachment;
import org.phoebus.olog.entity.Log;
import org.phoebus.olog.entity.Tag;
import org.phoebus.olog.entity.preprocess.CommonmarkPreprocessor;
import org.phoebus.olog.entity.preprocess.DefaultPreprocessor;
import org.phoebus.util.time.TimeParser;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/**
 * Resource for handling the requests to ../logs
 * @author kunal
 *
 */
@RestController
@RequestMapping(LOG_RESOURCE_URI)
public class LogResource
{
    @Autowired
    LogRepository logRepository;
    @Autowired
    AttachmentRepository attachmentRepository;
    @Autowired
    private LogbookRepository logbookRepository;
    @Autowired
    private TagRepository tagRepository;
    @Autowired
    private DefaultPreprocessor defaultPreprocessor;
    @Autowired
    private CommonmarkPreprocessor commonmarkPreprocessor;

    @GetMapping("{logId}")
    public Log getLog(@PathVariable String logId) {
        return logRepository.findById(logId).get();
    }

    @GetMapping("/attachments/{logId}/{attachmentName}")
    public ResponseEntity<Resource> findResources(@PathVariable String logId, @PathVariable String attachmentName)
    {
        Optional<Log> log = logRepository.findById(logId);
        if (log.isPresent())
        {
            Set<Attachment> attachments = log.get().getAttachments().stream().filter(attachment -> {
                return attachment.getFilename().equals(attachmentName);
            }).collect(Collectors.toSet());
            if (attachments.size() == 1)
            {
                Attachment foundAttachment = attachmentRepository.findById(attachments.iterator().next().getId()).get();
                InputStreamResource resource;
                try
                {
                    resource = new InputStreamResource(foundAttachment.getAttachment().getInputStream());
                    ContentDisposition contentDisposition = ContentDisposition.builder("attachment")
                            .filename(attachmentName)
                            .build();
                    HttpHeaders httpHeaders = new HttpHeaders();
                    httpHeaders.setContentDisposition(contentDisposition);
                    MediaType mediaType = ContentTypeResolver.determineMediaType(attachmentName);
                    if(mediaType != null){
                        httpHeaders.setContentType(mediaType);
                    }
                    return new ResponseEntity<>(resource, httpHeaders, HttpStatus.OK);
                } catch (IOException e)
                {
                    Logger.getLogger(LogResource.class.getName()).log(Level.WARNING,
                            String.format("Unable to retrieve attachment %s for log id %s", attachmentName, logId),
                            e);
                }
            } else
            {
                Logger.getLogger(LogResource.class.getName()).log(Level.WARNING,
                        String.format("Found %d attachments named %s for log id %s", attachments.size(), attachmentName, logId));
            }
        }
        return null;
    }

    /**
     * Finds matching log entries based on the specified search parameters.
     * @param allRequestParams A map of search query parameters. Note that this method supports date/time expressions
     *                         like "12 hours" or "2 days" as well as formatted strings like "2021-01-20 12:00:00.123".
     * @return A {@link List} of {@link Log} objects matching the query parameters, or an
     *  empty list if no matching logs are found.
     */
    @GetMapping()
    public List<Log> findLogs(@RequestParam MultiValueMap<String, String> allRequestParams) {
        for(String key : allRequestParams.keySet()){
            if("start".equalsIgnoreCase(key.toLowerCase()) || "end".equalsIgnoreCase(key.toLowerCase())){
                String value = allRequestParams.get(key).get(0);
                Object time = TimeParser.parseInstantOrTemporalAmount(value);
                if (time instanceof Instant) {
                    allRequestParams.get(key).clear();
                    allRequestParams.get(key).add(MILLI_FORMAT.format((Instant)time));
                } else if (time instanceof TemporalAmount) {
                    allRequestParams.get(key).clear();
                    allRequestParams.get(key).add(MILLI_FORMAT.format(Instant.now().minus((TemporalAmount)time)));
                }
            }
        }
        return logRepository.search(allRequestParams);
    }
    
    /**
     * 
     * @param log
     * @return
     */
    @PutMapping()
    public Log createLog(@RequestParam(value = "markup", required = false, defaultValue="none") String markup,
                         @Valid @RequestBody Log log,
                         @AuthenticationPrincipal Principal principal) {
        log.setOwner(principal.getName());
        Set<String> logbookNames = log.getLogbooks().stream().map(l -> l.getName()).collect(Collectors.toSet());
        Set<String> persistedLogbookNames = new HashSet<>();
        logbookRepository.findAll().forEach(l -> persistedLogbookNames.add(l.getName()));
        if(!CollectionUtils.containsAll(persistedLogbookNames, logbookNames)){
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "One or more invalid logbook name(s)");
        }
        Set<Tag> tags = log.getTags();
        if(tags != null && !tags.isEmpty()){
            Set<String> tagNames = tags.stream().map(t -> t.getName()).collect(Collectors.toSet());
            Set<String> persistedTags = new HashSet<>();
            tagRepository.findAll().forEach(t -> persistedTags.add(t.getName()));
            if(!CollectionUtils.containsAll(persistedTags, tagNames)){
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "One or more invalid tag name(s)");
            }
        }
        switch(markup){
            case "none":
            default:
                log = defaultPreprocessor.process(log);
                break;
            case "commonmark":
                log = commonmarkPreprocessor.process(log);
                break;
        }
        return logRepository.save(log);
    }

    @PostMapping("/attachments/{logId}")
    public Log uploadAttachment(@PathVariable String logId,
                                @RequestPart("file") MultipartFile file,
                                @RequestPart("filename") String filename,
                                @RequestPart(value = "id", required = false) String id,
                                @RequestPart(value = "fileMetadataDescription", required = false) String fileMetadataDescription) {
        Optional<Log> foundLog = logRepository.findById(logId);
        if (logRepository.findById(logId).isPresent())
        {
            filename = filename == null || filename.isEmpty() ? file.getName() : filename;
            fileMetadataDescription = fileMetadataDescription == null || fileMetadataDescription.isEmpty()
                    ? file.getContentType()
                    : fileMetadataDescription;
            Attachment attachment = new Attachment(id, file, filename, fileMetadataDescription);
            // Store the attachment
            Attachment createdAttachement = attachmentRepository.save(attachment);
            // Update the log entry with the id of the stored attachment
            Log log = foundLog.get();
            Set<Attachment> existingAttachments = log.getAttachments();
            existingAttachments.add(createdAttachement);
            log.setAttachments(existingAttachments);
            return logRepository.update(log);
        } else
        {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Failed to retrieve log with id " + logId);
        }
    }

    /**
     * Endpoint supporting upload of multiple files, i.e. saving the client from sending one POST request per file.
     * Calls {@link #uploadAttachment(String, MultipartFile, String, String, String)} internally, using the original file's
     * name and content type.
     * @param logId
     * @param files
     * @return
     */
    @PostMapping(value = "/attachments-multi/{logId}", consumes = "multipart/form-data")
    public Log uploadMultipleAttachments(@PathVariable String logId,
                                         @RequestPart("file") MultipartFile[] files) {
        if (logRepository.findById(logId).isPresent()) {
            for (MultipartFile file : files) {
                uploadAttachment(logId, file, file.getOriginalFilename(), file.getName(), file.getContentType());
            }
            return logRepository.findById(logId).get();
        } else {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Failed to retrieve log with id " + logId);
        }
    }
}