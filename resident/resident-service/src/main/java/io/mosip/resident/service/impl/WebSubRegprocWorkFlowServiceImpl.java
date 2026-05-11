package io.mosip.resident.service.impl;

import io.mosip.kernel.core.logger.spi.Logger;
import io.mosip.resident.config.LoggerConfiguration;
import io.mosip.resident.constant.EventStatusFailure;
import io.mosip.resident.constant.EventStatusInProgress;
import io.mosip.resident.constant.EventStatusSuccess;
import io.mosip.resident.constant.IdType;
import io.mosip.resident.constant.PacketStatus;
import io.mosip.resident.constant.RequestType;
import io.mosip.resident.constant.ResidentConstants;
import io.mosip.resident.constant.TemplateType;
import io.mosip.resident.dto.WorkflowCompletedEventDTO;
import io.mosip.resident.entity.ResidentTransactionEntity;
import io.mosip.resident.exception.ResidentServiceCheckedException;
import io.mosip.resident.repository.ResidentTransactionRepository;
import io.mosip.resident.service.WebSubRegprocWorkFlowService;
import io.mosip.resident.util.Utility;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Set;

/**
 * @author Kamesh Shekhar Prasad
 */

@Component
public class WebSubRegprocWorkFlowServiceImpl implements WebSubRegprocWorkFlowService {

    private static final Logger logger = LoggerConfiguration.logConfig(WebSubRegprocWorkFlowServiceImpl.class);

    @Autowired
    Environment environment;

    @Autowired
    ResidentTransactionRepository residentTransactionRepository;

    @Autowired
    Utility utility;

    private static final Set<String> CONTACT_DETAIL_ATTRIBUTES = Set.of("email", "phone");

    @Override
    public void updateResidentStatus(WorkflowCompletedEventDTO workflowCompletedEventDTO) throws ResidentServiceCheckedException {
        logger.debug(String.format("WebSubRegprocWorkFlowServiceImpl:updateResidentStatus entry instanceId=%s resultCode=%s workflowType=%s",
                workflowCompletedEventDTO.getInstanceId(), workflowCompletedEventDTO.getResultCode(),
                workflowCompletedEventDTO.getWorkflowType()));
        ResidentTransactionEntity residentTransactionEntity = null;
        String individualId = null;
        if (workflowCompletedEventDTO.getResultCode() != null) {
            if (workflowCompletedEventDTO.getInstanceId() != null) {
                residentTransactionEntity =
                        residentTransactionRepository.findTopByAidOrderByCrDtimesDesc(workflowCompletedEventDTO.getInstanceId());
                if (residentTransactionEntity == null) {
                    logger.debug(String.format("No resident transaction found for regproc callback aid=%s resultCode=%s",
                            workflowCompletedEventDTO.getInstanceId(), workflowCompletedEventDTO.getResultCode()));
                }
            } else {
                logger.debug(String.format("Regproc callback skipped because instanceId is null resultCode=%s",
                        workflowCompletedEventDTO.getResultCode()));
            }
            if (residentTransactionEntity != null) {
                individualId = residentTransactionEntity.getIndividualId();
                logger.debug(String.format("Regproc callback matched eventId=%s aid=%s currentStatus=%s attributes=%s credentialRequestId=%s",
                        residentTransactionEntity.getEventId(), residentTransactionEntity.getAid(),
                        residentTransactionEntity.getStatusCode(), residentTransactionEntity.getAttributeList(),
                        residentTransactionEntity.getCredentialRequestId()));
                logger.debug(String.format("Configured regproc success statuses=%s failure statuses=%s",
                        PacketStatus.getStatusCodeList(PacketStatus.SUCCESS, environment),
                        PacketStatus.getStatusCodeList(PacketStatus.FAILURE, environment)));
                if (PacketStatus.getStatusCodeList(PacketStatus.FAILURE, environment).contains(workflowCompletedEventDTO.getResultCode())) {
                    logger.debug(String.format("Updating resident transaction as FAILED for eventId=%s aid=%s resultCode=%s",
                            residentTransactionEntity.getEventId(), residentTransactionEntity.getAid(),
                            workflowCompletedEventDTO.getResultCode()));
                    utility.updateEntity(EventStatusFailure.FAILED.name(), RequestType.UPDATE_MY_UIN.name() + " - " + ResidentConstants.FAILED,
                            false, "Packet Failed in Regproc with status code-" +
                            workflowCompletedEventDTO.getResultCode(), residentTransactionEntity);
                    utility.sendNotification(residentTransactionEntity.getEventId(), individualId, TemplateType.REGPROC_FAILED);
                } else if (PacketStatus.getStatusCodeList(PacketStatus.SUCCESS, environment).contains(workflowCompletedEventDTO.getResultCode())) {
                    String statusCode = getStatusCodeForSuccessfulUpdate(residentTransactionEntity);
                    logger.debug(String.format("Updating resident transaction after successful regproc eventId=%s aid=%s attributes=%s newStatus=%s",
                            residentTransactionEntity.getEventId(), residentTransactionEntity.getAid(),
                            residentTransactionEntity.getAttributeList(), statusCode));
                    utility.updateEntity(statusCode, statusCode, false,
                            "Packet processed in Regproc with status code-" +
                            workflowCompletedEventDTO.getResultCode(), residentTransactionEntity);
                    utility.sendNotification(residentTransactionEntity.getEventId(), individualId, TemplateType.REGPROC_SUCCESS);
                } else {
                    logger.debug(String.format("Regproc callback resultCode did not match configured success/failure lists eventId=%s aid=%s resultCode=%s",
                            residentTransactionEntity.getEventId(), residentTransactionEntity.getAid(),
                            workflowCompletedEventDTO.getResultCode()));
                }
            }
        } else {
            logger.debug("Regproc callback skipped because resultCode is null");
        }
        logger.debug("WebSubRegprocWorkFlowServiceImpl:updateResidentStatus exit");
    }

    private String getStatusCodeForSuccessfulUpdate(ResidentTransactionEntity residentTransactionEntity) {
        if (isContactDetailsUpdate(residentTransactionEntity.getAttributeList())) {
            return EventStatusSuccess.DATA_UPDATED.name();
        }
        return EventStatusInProgress.IDENTITY_UPDATED.name();
    }

    private boolean isContactDetailsUpdate(String attributeList) {
        if (attributeList == null || attributeList.trim().isEmpty()) {
            return false;
        }
        Set<String> updateAttributes = Arrays.stream(attributeList.split(ResidentConstants.SEMI_COLON))
                .map(String::trim)
                .filter(attribute -> !attribute.isEmpty())
                .filter(attribute -> !IdType.NIN.name().equalsIgnoreCase(attribute))
                .map(String::toLowerCase)
                .collect(java.util.stream.Collectors.toSet());
        boolean contactDetailsUpdate = !updateAttributes.isEmpty() && CONTACT_DETAIL_ATTRIBUTES.containsAll(updateAttributes);
        logger.debug(String.format("Contact details update check originalAttributes=%s normalizedAttributes=%s result=%s",
                attributeList, updateAttributes, contactDetailsUpdate));
        return contactDetailsUpdate;
    }

}
