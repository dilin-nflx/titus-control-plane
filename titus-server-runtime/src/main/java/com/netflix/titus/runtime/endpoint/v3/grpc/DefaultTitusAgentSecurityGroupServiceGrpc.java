package com.netflix.titus.runtime.endpoint.v3.grpc;

import javax.inject.Inject;
import javax.inject.Singleton;

import com.netflix.titus.grpc.protogen.JobManagementServiceGrpc;
import com.netflix.titus.runtime.service.TitusAgentSecurityGroupService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.netflix.titus.api.jobmanager.service.JobManagerConstants;
import com.netflix.titus.api.model.callmetadata.CallMetadata;
import com.netflix.titus.TitusAgentSecurityGroupServiceGrpc;
import com.netflix.titus.TitusVpcApi.ResetSecurityGroupRequest;
import com.netflix.titus.TitusVpcApi.ResetSecurityGroupResponse;
import com.netflix.titus.runtime.endpoint.metadata.CallMetadataResolver;

import com.netflix.titus.TitusAgentSecurityGroupServiceGrpc.TitusAgentSecurityGroupServiceStub;
import rx.Subscription;

import static com.netflix.titus.runtime.endpoint.common.grpc.GrpcUtil.attachCancellingCallback;
import static com.netflix.titus.runtime.endpoint.common.grpc.GrpcUtil.safeOnError;


@Singleton
public class DefaultTitusAgentSecurityGroupServiceGrpc  extends TitusAgentSecurityGroupServiceGrpc.TitusAgentSecurityGroupServiceImplBase {
    private static final Logger logger = LoggerFactory.getLogger(DefaultJobActivityHistoryServiceGrpc.class);
    private final TitusAgentSecurityGroupService titusAgentSecurityGroupServiceGateway;
    private final CallMetadataResolver callMetadataResolver;

    @Inject
    public DefaultTitusAgentSecurityGroupServiceGrpc(TitusAgentSecurityGroupService titusAgentSecurityGroupService, //client
                                                     CallMetadataResolver callMetadataResolver) {
        this.titusAgentSecurityGroupServiceGateway = titusAgentSecurityGroupService;
        this.callMetadataResolver = callMetadataResolver;
    }

    @Override
    public void resetSecurityGroup(com.netflix.titus.TitusVpcApi.ResetSecurityGroupRequest request,
                                   io.grpc.stub.StreamObserver<com.netflix.titus.TitusVpcApi.ResetSecurityGroupResponse> responseObserver) {
        Subscription subscription =
                titusAgentSecurityGroupServiceGateway.ResetSecurityGroup(request, resolveCallMetadata()).subscribe(
                        response -> responseObserver.onNext(ResetSecurityGroupResponse.newBuilder().build()), //TODO: Ask @ghartmann
                        e -> safeOnError(logger, e, responseObserver),
                        responseObserver::onCompleted
                        );
        attachCancellingCallback(responseObserver, subscription);
    }

    private CallMetadata resolveCallMetadata() {
        return callMetadataResolver.resolve().orElse(JobManagerConstants.UNDEFINED_CALL_METADATA);
    }
}

