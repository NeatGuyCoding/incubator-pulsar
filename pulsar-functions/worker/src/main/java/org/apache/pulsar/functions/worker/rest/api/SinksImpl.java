/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.pulsar.functions.worker.rest.api;

import static org.apache.commons.lang3.StringUtils.isBlank;
import static org.apache.commons.lang3.StringUtils.isNotBlank;
import static org.apache.pulsar.functions.auth.FunctionAuthUtils.getFunctionAuthData;
import static org.apache.pulsar.functions.utils.FunctionCommon.isFunctionCodeBuiltin;
import static org.apache.pulsar.functions.worker.rest.RestUtils.throwUnavailableException;
import com.google.protobuf.ByteString;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Response;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.pulsar.broker.authentication.AuthenticationParameters;
import org.apache.pulsar.client.admin.PulsarAdminException;
import org.apache.pulsar.common.functions.UpdateOptionsImpl;
import org.apache.pulsar.common.functions.Utils;
import org.apache.pulsar.common.io.ConfigFieldDefinition;
import org.apache.pulsar.common.io.ConnectorDefinition;
import org.apache.pulsar.common.io.SinkConfig;
import org.apache.pulsar.common.policies.data.ExceptionInformation;
import org.apache.pulsar.common.policies.data.SinkStatus;
import org.apache.pulsar.common.util.RestException;
import org.apache.pulsar.functions.auth.FunctionAuthData;
import org.apache.pulsar.functions.instance.InstanceUtils;
import org.apache.pulsar.functions.proto.Function;
import org.apache.pulsar.functions.proto.InstanceCommunication;
import org.apache.pulsar.functions.utils.ComponentTypeUtils;
import org.apache.pulsar.functions.utils.FunctionFilePackage;
import org.apache.pulsar.functions.utils.FunctionMetaDataUtils;
import org.apache.pulsar.functions.utils.SinkConfigUtils;
import org.apache.pulsar.functions.utils.ValidatableFunctionPackage;
import org.apache.pulsar.functions.utils.io.Connector;
import org.apache.pulsar.functions.worker.FunctionMetaDataManager;
import org.apache.pulsar.functions.worker.PulsarWorkerService;
import org.apache.pulsar.functions.worker.WorkerConfig;
import org.apache.pulsar.functions.worker.WorkerUtils;
import org.apache.pulsar.functions.worker.service.api.Sinks;
import org.glassfish.jersey.media.multipart.FormDataContentDisposition;

@Slf4j
public class SinksImpl extends ComponentImpl implements Sinks<PulsarWorkerService> {

    public SinksImpl(Supplier<PulsarWorkerService> workerServiceSupplier) {
        super(workerServiceSupplier, Function.FunctionDetails.ComponentType.SINK);
    }

    @Override
    public void registerSink(final String tenant,
                             final String namespace,
                             final String sinkName,
                             final InputStream uploadedInputStream,
                             final FormDataContentDisposition fileDetail,
                             final String sinkPkgUrl,
                             final SinkConfig sinkConfig,
                             final AuthenticationParameters authParams) {

        if (!isWorkerServiceAvailable()) {
            throwUnavailableException();
        }

        if (tenant == null) {
            throw new RestException(Response.Status.BAD_REQUEST, "Tenant is not provided");
        }
        if (namespace == null) {
            throw new RestException(Response.Status.BAD_REQUEST, "Namespace is not provided");
        }
        if (sinkName == null) {
            throw new RestException(Response.Status.BAD_REQUEST, "Sink name is not provided");
        }
        if (sinkConfig == null) {
            throw new RestException(Response.Status.BAD_REQUEST, "Sink config is not provided");
        }

        throwRestExceptionIfUnauthorizedForNamespace(tenant, namespace, sinkName, "register", authParams);

        try {
            // Check tenant exists
            worker().getBrokerAdmin().tenants().getTenantInfo(tenant);

            String qualifiedNamespace = tenant + "/" + namespace;
            List<String> namespaces = worker().getBrokerAdmin().namespaces().getNamespaces(tenant);
            if (namespaces != null && !namespaces.contains(qualifiedNamespace)) {
                String qualifiedNamespaceWithCluster = String.format("%s/%s/%s", tenant,
                        worker().getWorkerConfig().getPulsarFunctionsCluster(), namespace);
                if (namespaces != null && !namespaces.contains(qualifiedNamespaceWithCluster)) {
                    log.error("{}/{}/{} Namespace {} does not exist", tenant, namespace, sinkName, namespace);
                    throw new RestException(Response.Status.BAD_REQUEST, "Namespace does not exist");
                }
            }
        } catch (PulsarAdminException.NotAuthorizedException e) {
            log.error("{}/{}/{} Client is not authorized to operate {} on tenant", tenant, namespace,
                    sinkName, ComponentTypeUtils.toString(componentType));
            throw new RestException(Response.Status.UNAUTHORIZED, "Client is not authorized to perform operation");
        } catch (PulsarAdminException.NotFoundException e) {
            log.error("{}/{}/{} Tenant {} does not exist", tenant, namespace, sinkName, tenant);
            throw new RestException(Response.Status.BAD_REQUEST, "Tenant does not exist");
        } catch (PulsarAdminException e) {
            log.error("{}/{}/{} Issues getting tenant data", tenant, namespace, sinkName, e);
            throw new RestException(Response.Status.INTERNAL_SERVER_ERROR, e.getMessage());
        }

        FunctionMetaDataManager functionMetaDataManager = worker().getFunctionMetaDataManager();

        if (functionMetaDataManager.containsFunction(tenant, namespace, sinkName)) {
            log.error("{} {}/{}/{} already exists", ComponentTypeUtils.toString(componentType), tenant, namespace,
                    sinkName);
            throw new RestException(Response.Status.BAD_REQUEST,
                    String.format("%s %s already exists", ComponentTypeUtils.toString(componentType), sinkName));
        }

        Function.FunctionDetails functionDetails;
        File componentPackageFile = null;
        try {

            // validate parameters
            try {
                if (isNotBlank(sinkPkgUrl)) {
                    componentPackageFile = getPackageFile(componentType, sinkPkgUrl);
                    functionDetails = validateUpdateRequestParams(tenant, namespace, sinkName,
                            sinkConfig, componentPackageFile);
                } else {
                    if (uploadedInputStream != null) {
                        componentPackageFile = WorkerUtils.dumpToTmpFile(uploadedInputStream);
                    }
                    functionDetails = validateUpdateRequestParams(tenant, namespace, sinkName,
                            sinkConfig, componentPackageFile);
                    if (!isFunctionCodeBuiltin(functionDetails)
                            && (componentPackageFile == null || fileDetail == null)) {
                        throw new IllegalArgumentException(
                                ComponentTypeUtils.toString(componentType) + " Package is not provided");
                    }
                }
            } catch (Exception e) {
                log.error("Invalid register {} request @ /{}/{}/{}", ComponentTypeUtils.toString(componentType), tenant,
                        namespace, sinkName, e);
                throw new RestException(Response.Status.BAD_REQUEST, e.getMessage());
            }

            try {
                worker().getFunctionRuntimeManager().getRuntimeFactory().doAdmissionChecks(functionDetails);
            } catch (Exception e) {
                log.error("{} {}/{}/{} cannot be admitted by the runtime factory",
                        ComponentTypeUtils.toString(componentType), tenant, namespace, sinkName);
                throw new RestException(Response.Status.BAD_REQUEST,
                        String.format("%s %s cannot be admitted:- %s", ComponentTypeUtils.toString(componentType),
                                sinkName, e.getMessage()));
            }

            // function state
            Function.FunctionMetaData.Builder functionMetaDataBuilder = Function.FunctionMetaData.newBuilder()
                    .setFunctionDetails(functionDetails)
                    .setCreateTime(System.currentTimeMillis())
                    .setVersion(0);

            // cache auth if need
            if (worker().getWorkerConfig().isAuthenticationEnabled()) {
                Function.FunctionDetails finalFunctionDetails = functionDetails;
                worker().getFunctionRuntimeManager()
                        .getRuntimeFactory()
                        .getAuthProvider().ifPresent(functionAuthProvider -> {
                    if (authParams.getClientAuthenticationDataSource() != null) {

                        try {
                            Optional<FunctionAuthData> functionAuthData = functionAuthProvider
                                    .cacheAuthData(finalFunctionDetails,
                                            authParams.getClientAuthenticationDataSource());

                            functionAuthData.ifPresent(authData -> functionMetaDataBuilder.setFunctionAuthSpec(
                                    Function.FunctionAuthenticationSpec.newBuilder()
                                            .setData(ByteString.copyFrom(authData.getData()))
                                            .build()));
                        } catch (Exception e) {
                            log.error("Error caching authentication data for {} {}/{}/{}",
                                    ComponentTypeUtils.toString(componentType), tenant, namespace, sinkName, e);


                            throw new RestException(Response.Status.INTERNAL_SERVER_ERROR,
                                    String.format("Error caching authentication data for %s %s:- %s",
                                            ComponentTypeUtils.toString(componentType), sinkName, e.getMessage()));
                        }
                    }
                });
            }

            Function.PackageLocationMetaData.Builder packageLocationMetaDataBuilder;
            try {
                packageLocationMetaDataBuilder = getFunctionPackageLocation(functionMetaDataBuilder.build(),
                        sinkPkgUrl, fileDetail, componentPackageFile);
            } catch (Exception e) {
                log.error("Failed process {} {}/{}/{} package: ", ComponentTypeUtils.toString(componentType), tenant,
                        namespace, sinkName, e);
                throw new RestException(Response.Status.INTERNAL_SERVER_ERROR, e.getMessage());
            }

            functionMetaDataBuilder.setPackageLocation(packageLocationMetaDataBuilder);

            String transformFunction = sinkConfig.getTransformFunction();
            if (isNotBlank(transformFunction)) {
                setTransformFunctionPackageLocation(functionMetaDataBuilder, functionDetails, transformFunction);
            }

            updateRequest(null, functionMetaDataBuilder.build());
        } finally {
            if (componentPackageFile != null && componentPackageFile.exists()) {
                if (sinkPkgUrl == null || !sinkPkgUrl.startsWith(Utils.FILE)) {
                    componentPackageFile.delete();
                }
            }
        }
    }

    @Override
    public void updateSink(final String tenant,
                           final String namespace,
                           final String sinkName,
                           final InputStream uploadedInputStream,
                           final FormDataContentDisposition fileDetail,
                           final String sinkPkgUrl,
                           final SinkConfig sinkConfig,
                           final AuthenticationParameters authParams,
                           UpdateOptionsImpl updateOptions) {

        if (!isWorkerServiceAvailable()) {
            throwUnavailableException();
        }

        if (tenant == null) {
            throw new RestException(Response.Status.BAD_REQUEST, "Tenant is not provided");
        }
        if (namespace == null) {
            throw new RestException(Response.Status.BAD_REQUEST, "Namespace is not provided");
        }
        if (sinkName == null) {
            throw new RestException(Response.Status.BAD_REQUEST, "Sink name is not provided");
        }
        if (sinkConfig == null) {
            throw new RestException(Response.Status.BAD_REQUEST, "Sink config is not provided");
        }

        throwRestExceptionIfUnauthorizedForNamespace(tenant, namespace, sinkName, "update", authParams);

        FunctionMetaDataManager functionMetaDataManager = worker().getFunctionMetaDataManager();

        if (!functionMetaDataManager.containsFunction(tenant, namespace, sinkName)) {
            throw new RestException(Response.Status.BAD_REQUEST,
                    String.format("%s %s doesn't exist", ComponentTypeUtils.toString(componentType), sinkName));
        }

        Function.FunctionMetaData existingComponent =
                functionMetaDataManager.getFunctionMetaData(tenant, namespace, sinkName);

        if (!InstanceUtils.calculateSubjectType(existingComponent.getFunctionDetails()).equals(componentType)) {
            log.error("{}/{}/{} is not a {}", tenant, namespace, sinkName, ComponentTypeUtils.toString(componentType));
            throw new RestException(Response.Status.NOT_FOUND,
                    String.format("%s %s doesn't exist", ComponentTypeUtils.toString(componentType), sinkName));
        }


        SinkConfig existingSinkConfig = SinkConfigUtils.convertFromDetails(existingComponent.getFunctionDetails());
        // The rest end points take precedence over whatever is there in functionconfig
        sinkConfig.setTenant(tenant);
        sinkConfig.setNamespace(namespace);
        sinkConfig.setName(sinkName);

        SinkConfig mergedConfig;
        try {
            mergedConfig = SinkConfigUtils.validateUpdate(existingSinkConfig, sinkConfig);
        } catch (Exception e) {
            throw new RestException(Response.Status.BAD_REQUEST, e.getMessage());
        }

        if (existingSinkConfig.equals(mergedConfig) && isBlank(sinkPkgUrl) && uploadedInputStream == null
                && (updateOptions == null || !updateOptions.isUpdateAuthData())) {
            log.error("{}/{}/{} Update contains no changes", tenant, namespace, sinkName);
            throw new RestException(Response.Status.BAD_REQUEST, "Update contains no change");
        }

        Function.FunctionDetails functionDetails;
        File componentPackageFile = null;
        try {

            // validate parameters
            try {
                componentPackageFile = getPackageFile(
                        componentType,
                        sinkPkgUrl,
                        existingComponent.getPackageLocation().getPackagePath(),
                        uploadedInputStream);
                functionDetails = validateUpdateRequestParams(tenant, namespace, sinkName,
                        mergedConfig, componentPackageFile);
                if (existingComponent.getPackageLocation().getPackagePath().startsWith(Utils.BUILTIN)
                        && !isFunctionCodeBuiltin(functionDetails)
                        && (componentPackageFile == null || fileDetail == null)) {
                        throw new IllegalArgumentException(
                                ComponentTypeUtils.toString(componentType) + " Package is not provided");
                    }
            } catch (Exception e) {
                log.error("Invalid update {} request @ /{}/{}/{}", ComponentTypeUtils.toString(componentType), tenant,
                        namespace, sinkName, e);
                throw new RestException(Response.Status.BAD_REQUEST, e.getMessage());
            }

            try {
                worker().getFunctionRuntimeManager().getRuntimeFactory().doAdmissionChecks(functionDetails);
            } catch (Exception e) {
                log.error("Updated {} {}/{}/{} cannot be submitted to runtime factory",
                        ComponentTypeUtils.toString(componentType), tenant, namespace, sinkName);
                throw new RestException(Response.Status.BAD_REQUEST, String.format("%s %s cannot be admitted:- %s",
                        ComponentTypeUtils.toString(componentType), sinkName, e.getMessage()));
            }

            // merge from existing metadata
            Function.FunctionMetaData.Builder functionMetaDataBuilder =
                    Function.FunctionMetaData.newBuilder().mergeFrom(existingComponent)
                            .setFunctionDetails(functionDetails);

            // update auth data if need
            if (worker().getWorkerConfig().isAuthenticationEnabled()) {
                Function.FunctionDetails finalFunctionDetails = functionDetails;
                worker().getFunctionRuntimeManager()
                        .getRuntimeFactory()
                        .getAuthProvider().ifPresent(functionAuthProvider -> {
                    if (authParams.getClientAuthenticationDataSource() != null && updateOptions != null
                            && updateOptions.isUpdateAuthData()) {
                        // get existing auth data if it exists
                        Optional<FunctionAuthData> existingFunctionAuthData = Optional.empty();
                        if (functionMetaDataBuilder.hasFunctionAuthSpec()) {
                            existingFunctionAuthData = Optional.ofNullable(getFunctionAuthData(
                                    Optional.ofNullable(functionMetaDataBuilder.getFunctionAuthSpec())));
                        }

                        try {
                            Optional<FunctionAuthData> newFunctionAuthData = functionAuthProvider
                                    .updateAuthData(finalFunctionDetails, existingFunctionAuthData,
                                            authParams.getClientAuthenticationDataSource());

                            if (newFunctionAuthData.isPresent()) {
                                functionMetaDataBuilder.setFunctionAuthSpec(
                                        Function.FunctionAuthenticationSpec.newBuilder()
                                                .setData(ByteString.copyFrom(newFunctionAuthData.get().getData()))
                                                .build());
                            } else {
                                functionMetaDataBuilder.clearFunctionAuthSpec();
                            }
                        } catch (Exception e) {
                            log.error("Error updating authentication data for {} {}/{}/{}",
                                    ComponentTypeUtils.toString(componentType), tenant, namespace, sinkName, e);
                            throw new RestException(Response.Status.INTERNAL_SERVER_ERROR,
                                    String.format("Error caching authentication data for %s %s:- %s",
                                            ComponentTypeUtils.toString(componentType), sinkName, e.getMessage()));
                        }
                    }
                });
            }

            Function.PackageLocationMetaData.Builder packageLocationMetaDataBuilder;
            if (isNotBlank(sinkPkgUrl) || uploadedInputStream != null) {
                Function.FunctionMetaData metaData = functionMetaDataBuilder.build();
                metaData = FunctionMetaDataUtils.incrMetadataVersion(metaData, metaData);
                try {
                    packageLocationMetaDataBuilder = getFunctionPackageLocation(metaData,
                            sinkPkgUrl, fileDetail, componentPackageFile);
                } catch (Exception e) {
                    log.error("Failed process {} {}/{}/{} package: ", ComponentTypeUtils.toString(componentType),
                            tenant, namespace, sinkName, e);
                    throw new RestException(Response.Status.INTERNAL_SERVER_ERROR, e.getMessage());
                }
            } else {
                packageLocationMetaDataBuilder =
                        Function.PackageLocationMetaData.newBuilder().mergeFrom(existingComponent.getPackageLocation());
            }

            functionMetaDataBuilder.setPackageLocation(packageLocationMetaDataBuilder);

            String transformFunction = mergedConfig.getTransformFunction();
            if (isNotBlank(transformFunction)
                    && !transformFunction.equals(existingSinkConfig.getTransformFunction())) {
                setTransformFunctionPackageLocation(functionMetaDataBuilder, functionDetails, transformFunction);
            }

            updateRequest(existingComponent, functionMetaDataBuilder.build());
        } finally {
            if (componentPackageFile != null && componentPackageFile.exists()) {
                if ((sinkPkgUrl != null && !sinkPkgUrl.startsWith(Utils.FILE)) || uploadedInputStream != null) {
                    componentPackageFile.delete();
                }
            }
        }
    }

    private void setTransformFunctionPackageLocation(Function.FunctionMetaData.Builder functionMetaDataBuilder,
                                                 Function.FunctionDetails functionDetails, String transformFunction) {
        File functionPackageFile = null;
        try {
            String builtin = functionDetails.getBuiltin();
            if (isBlank(builtin)) {
                functionPackageFile =
                        getPackageFile(Function.FunctionDetails.ComponentType.FUNCTION, transformFunction);
            }
            Function.PackageLocationMetaData.Builder functionPackageLocation =
                    getFunctionPackageLocation(functionMetaDataBuilder.build(),
                            transformFunction, null, functionPackageFile,
                            functionDetails.getName() + "__sink-function",
                            Function.FunctionDetails.ComponentType.FUNCTION, builtin);
            functionMetaDataBuilder.setTransformFunctionPackageLocation(functionPackageLocation);
        } catch (Exception e) {
            log.error("Failed process {} {}/{}/{} extra function package: ",
                    ComponentTypeUtils.toString(componentType), functionDetails.getTenant(),
                    functionDetails.getNamespace(), functionDetails.getName(), e);
            throw new RestException(Response.Status.INTERNAL_SERVER_ERROR, e.getMessage());
        } finally {
            if (functionPackageFile != null && functionPackageFile.exists()) {
                if (!transformFunction.startsWith(Utils.FILE)) {
                    functionPackageFile.delete();
                }
            }
        }
    }

    private class GetSinkStatus extends GetStatus<SinkStatus, SinkStatus.SinkInstanceStatus.SinkInstanceStatusData> {

        @Override
        public SinkStatus.SinkInstanceStatus.SinkInstanceStatusData notScheduledInstance() {
            SinkStatus.SinkInstanceStatus.SinkInstanceStatusData sinkInstanceStatusData =
                    new SinkStatus.SinkInstanceStatus.SinkInstanceStatusData();
            sinkInstanceStatusData.setRunning(false);
            sinkInstanceStatusData.setError("Sink has not been scheduled");
            return sinkInstanceStatusData;
        }

        @Override
        public SinkStatus.SinkInstanceStatus.SinkInstanceStatusData fromFunctionStatusProto(
                InstanceCommunication.FunctionStatus status,
                String assignedWorkerId) {
            SinkStatus.SinkInstanceStatus.SinkInstanceStatusData sinkInstanceStatusData =
                    new SinkStatus.SinkInstanceStatus.SinkInstanceStatusData();
            sinkInstanceStatusData.setRunning(status.getRunning());
            sinkInstanceStatusData.setError(status.getFailureException());
            sinkInstanceStatusData.setNumRestarts(status.getNumRestarts());
            sinkInstanceStatusData.setNumReadFromPulsar(status.getNumReceived());

            // We treat source/user/system exceptions returned from function as system exceptions
            sinkInstanceStatusData.setNumSystemExceptions(status.getNumSystemExceptions()
                    + status.getNumUserExceptions() + status.getNumSourceExceptions());
            List<ExceptionInformation> systemExceptionInformationList = new LinkedList<>();
            for (InstanceCommunication.FunctionStatus.ExceptionInformation exceptionEntry :
                    status.getLatestUserExceptionsList()) {
                ExceptionInformation exceptionInformation = getExceptionInformation(exceptionEntry);
                systemExceptionInformationList.add(exceptionInformation);
            }

            for (InstanceCommunication.FunctionStatus.ExceptionInformation exceptionEntry :
                    status.getLatestSystemExceptionsList()) {
                ExceptionInformation exceptionInformation = getExceptionInformation(exceptionEntry);
                systemExceptionInformationList.add(exceptionInformation);
            }

            for (InstanceCommunication.FunctionStatus.ExceptionInformation exceptionEntry :
                    status.getLatestSourceExceptionsList()) {
                ExceptionInformation exceptionInformation = getExceptionInformation(exceptionEntry);
                systemExceptionInformationList.add(exceptionInformation);
            }
            sinkInstanceStatusData.setLatestSystemExceptions(systemExceptionInformationList);

            sinkInstanceStatusData.setNumSinkExceptions(status.getNumSinkExceptions());
            List<ExceptionInformation> sinkExceptionInformationList = new LinkedList<>();
            for (InstanceCommunication.FunctionStatus.ExceptionInformation exceptionEntry :
                    status.getLatestSinkExceptionsList()) {
                ExceptionInformation exceptionInformation = getExceptionInformation(exceptionEntry);
                sinkExceptionInformationList.add(exceptionInformation);
            }
            sinkInstanceStatusData.setLatestSinkExceptions(sinkExceptionInformationList);

            sinkInstanceStatusData.setNumWrittenToSink(status.getNumSuccessfullyProcessed());
            sinkInstanceStatusData.setLastReceivedTime(status.getLastInvocationTime());
            sinkInstanceStatusData.setWorkerId(assignedWorkerId);

            return sinkInstanceStatusData;
        }

        @Override
        public SinkStatus.SinkInstanceStatus.SinkInstanceStatusData notRunning(String assignedWorkerId, String error) {
            SinkStatus.SinkInstanceStatus.SinkInstanceStatusData sinkInstanceStatusData =
                    new SinkStatus.SinkInstanceStatus.SinkInstanceStatusData();
            sinkInstanceStatusData.setRunning(false);
            if (error != null) {
                sinkInstanceStatusData.setError(error);
            }
            sinkInstanceStatusData.setWorkerId(assignedWorkerId);

            return sinkInstanceStatusData;
        }

        @Override
        public SinkStatus getStatus(final String tenant,
                                    final String namespace,
                                    final String name,
                                    final Collection<Function.Assignment> assignments,
                                    final URI uri) throws PulsarAdminException {
            SinkStatus sinkStatus = new SinkStatus();
            for (Function.Assignment assignment : assignments) {
                boolean isOwner = worker().getWorkerConfig().getWorkerId().equals(assignment.getWorkerId());
                SinkStatus.SinkInstanceStatus.SinkInstanceStatusData sinkInstanceStatusData;
                if (isOwner) {
                    sinkInstanceStatusData = getComponentInstanceStatus(tenant,
                            namespace, name, assignment.getInstance().getInstanceId(), null);
                } else {
                    sinkInstanceStatusData = worker().getFunctionAdmin().sinks().getSinkStatus(
                            assignment.getInstance().getFunctionMetaData().getFunctionDetails().getTenant(),
                            assignment.getInstance().getFunctionMetaData().getFunctionDetails().getNamespace(),
                            assignment.getInstance().getFunctionMetaData().getFunctionDetails().getName(),
                            assignment.getInstance().getInstanceId());
                }

                SinkStatus.SinkInstanceStatus instanceStatus = new SinkStatus.SinkInstanceStatus();
                instanceStatus.setInstanceId(assignment.getInstance().getInstanceId());
                instanceStatus.setStatus(sinkInstanceStatusData);
                sinkStatus.addInstance(instanceStatus);
            }

            sinkStatus.setNumInstances(sinkStatus.instances.size());
            sinkStatus.getInstances().forEach(sinkInstanceStatus -> {
                if (sinkInstanceStatus.getStatus().isRunning()) {
                    sinkStatus.numRunning++;
                }
            });
            return sinkStatus;
        }

        @Override
        public SinkStatus getStatusExternal(final String tenant,
                                            final String namespace,
                                            final String name,
                                            final int parallelism) {
            SinkStatus sinkStatus = new SinkStatus();
            for (int i = 0; i < parallelism; ++i) {
                SinkStatus.SinkInstanceStatus.SinkInstanceStatusData sinkInstanceStatusData =
                        getComponentInstanceStatus(tenant, namespace, name, i, null);
                SinkStatus.SinkInstanceStatus sinkInstanceStatus =
                        new SinkStatus.SinkInstanceStatus();
                sinkInstanceStatus.setInstanceId(i);
                sinkInstanceStatus.setStatus(sinkInstanceStatusData);
                sinkStatus.addInstance(sinkInstanceStatus);
            }

            sinkStatus.setNumInstances(sinkStatus.instances.size());
            sinkStatus.getInstances().forEach(sinkInstanceStatus -> {
                if (sinkInstanceStatus.getStatus().isRunning()) {
                    sinkStatus.numRunning++;
                }
            });
            return sinkStatus;
        }

        @Override
        public SinkStatus emptyStatus(final int parallelism) {
            SinkStatus sinkStatus = new SinkStatus();
            sinkStatus.setNumInstances(parallelism);
            sinkStatus.setNumRunning(0);
            for (int i = 0; i < parallelism; i++) {
                SinkStatus.SinkInstanceStatus sinkInstanceStatus = new SinkStatus.SinkInstanceStatus();
                sinkInstanceStatus.setInstanceId(i);
                SinkStatus.SinkInstanceStatus.SinkInstanceStatusData sinkInstanceStatusData =
                        new SinkStatus.SinkInstanceStatus.SinkInstanceStatusData();
                sinkInstanceStatusData.setRunning(false);
                sinkInstanceStatusData.setError("Sink has not been scheduled");
                sinkInstanceStatus.setStatus(sinkInstanceStatusData);

                sinkStatus.addInstance(sinkInstanceStatus);
            }

            return sinkStatus;
        }
    }

    private ExceptionInformation getExceptionInformation(InstanceCommunication.FunctionStatus.ExceptionInformation
                                                                 exceptionEntry) {
        ExceptionInformation exceptionInformation =
                new ExceptionInformation();
        exceptionInformation.setTimestampMs(exceptionEntry.getMsSinceEpoch());
        exceptionInformation.setExceptionString(exceptionEntry.getExceptionString());
        return exceptionInformation;
    }

    @Override
    public SinkStatus.SinkInstanceStatus.SinkInstanceStatusData
    getSinkInstanceStatus(final String tenant,
                          final String namespace,
                          final String sinkName,
                          final String instanceId,
                          final URI uri,
                          final AuthenticationParameters authParams) {

        // validate parameters
        componentInstanceStatusRequestValidate(tenant, namespace, sinkName, Integer.parseInt(instanceId),
                authParams);


        SinkStatus.SinkInstanceStatus.SinkInstanceStatusData sinkInstanceStatusData;
        try {
            sinkInstanceStatusData = new GetSinkStatus().getComponentInstanceStatus(tenant, namespace, sinkName,
                    Integer.parseInt(instanceId), uri);
        } catch (WebApplicationException we) {
            throw we;
        } catch (Exception e) {
            log.error("{}/{}/{} Got Exception Getting Status", tenant, namespace, sinkName, e);
            throw new RestException(Response.Status.INTERNAL_SERVER_ERROR, e.getMessage());
        }
        return sinkInstanceStatusData;
    }

    @Override
    public SinkStatus getSinkStatus(final String tenant,
                                    final String namespace,
                                    final String componentName,
                                    final URI uri,
                                    final AuthenticationParameters authParams) {

        // validate parameters
        componentStatusRequestValidate(tenant, namespace, componentName, authParams);

        SinkStatus sinkStatus;
        try {
            sinkStatus = new GetSinkStatus().getComponentStatus(tenant, namespace, componentName, uri);
        } catch (WebApplicationException we) {
            throw we;
        } catch (Exception e) {
            log.error("{}/{}/{} Got Exception Getting Status", tenant, namespace, componentName, e);
            throw new RestException(Response.Status.INTERNAL_SERVER_ERROR, e.getMessage());
        }

        return sinkStatus;
    }

    @Override
    public SinkConfig getSinkInfo(final String tenant,
                                  final String namespace,
                                  final String componentName,
                                  final AuthenticationParameters authParams) {
        componentStatusRequestValidate(tenant, namespace, componentName, authParams);
        Function.FunctionMetaData functionMetaData =
                worker().getFunctionMetaDataManager().getFunctionMetaData(tenant, namespace, componentName);
        return SinkConfigUtils.convertFromDetails(functionMetaData.getFunctionDetails());
    }

    @Override
    public List<ConnectorDefinition> getSinkList() {
        List<ConnectorDefinition> connectorDefinitions = getListOfConnectors();
        List<ConnectorDefinition> retval = new ArrayList<>();
        for (ConnectorDefinition connectorDefinition : connectorDefinitions) {
            if (!org.apache.commons.lang3.StringUtils.isEmpty(connectorDefinition.getSinkClass())) {
                retval.add(connectorDefinition);
            }
        }
        return retval;
    }

    @Override
    public List<ConfigFieldDefinition> getSinkConfigDefinition(String name) {
        if (!isWorkerServiceAvailable()) {
            throwUnavailableException();
        }
        List<ConfigFieldDefinition> retval = this.worker().getConnectorsManager().getSinkConfigDefinition(name);
        if (retval == null) {
            throw new RestException(Response.Status.NOT_FOUND, "builtin sink does not exist");
        }
        return retval;
    }

    private Function.FunctionDetails validateUpdateRequestParams(final String tenant,
                                                                 final String namespace,
                                                                 final String sinkName,
                                                                 final SinkConfig sinkConfig,
                                                                 final File sinkPackageFile)
            throws IOException, PulsarAdminException {

        // The rest end points take precedence over whatever is there in sinkConfig
        sinkConfig.setTenant(tenant);
        sinkConfig.setNamespace(namespace);
        sinkConfig.setName(sinkName);
        org.apache.pulsar.common.functions.Utils.inferMissingArguments(sinkConfig);

        ValidatableFunctionPackage connectorFunctionPackage = null;
        // check if sink is builtin and extract classloader
        if (!StringUtils.isEmpty(sinkConfig.getArchive())) {
            String archive = sinkConfig.getArchive();
            if (archive.startsWith(org.apache.pulsar.common.functions.Utils.BUILTIN)) {
                archive = archive.replaceFirst("^builtin://", "");

                Connector connector = worker().getConnectorsManager().getConnector(archive);
                // check if builtin connector exists
                if (connector == null) {
                    throw new IllegalArgumentException("Built-in sink is not available");
                }
                connectorFunctionPackage = connector.getConnectorFunctionPackage();
            }
        }

        boolean shouldCloseFunctionPackage = false;
        ValidatableFunctionPackage transformFunctionPackage = null;
        boolean shouldCloseTransformFunctionPackage = false;
        try {

            // if sink is not builtin, attempt to extract classloader from package file if it exists
            WorkerConfig workerConfig = worker().getWorkerConfig();
            if (connectorFunctionPackage == null && sinkPackageFile != null) {
                connectorFunctionPackage =
                        new FunctionFilePackage(sinkPackageFile, workerConfig.getNarExtractionDirectory(),
                                workerConfig.getEnableClassloadingOfExternalFiles(), ConnectorDefinition.class);
                shouldCloseFunctionPackage = true;
            }

            if (connectorFunctionPackage == null) {
                throw new IllegalArgumentException("Sink package is not provided");
            }

            if (isNotBlank(sinkConfig.getTransformFunction())) {
                transformFunctionPackage =
                        getBuiltinFunctionPackage(sinkConfig.getTransformFunction());
                if (transformFunctionPackage == null) {
                    File functionPackageFile = getPackageFile(Function.FunctionDetails.ComponentType.FUNCTION,
                            sinkConfig.getTransformFunction());
                    transformFunctionPackage =
                            new FunctionFilePackage(functionPackageFile, workerConfig.getNarExtractionDirectory(),
                                    workerConfig.getEnableClassloadingOfExternalFiles(), ConnectorDefinition.class);
                    shouldCloseTransformFunctionPackage = true;
                }
                if (transformFunctionPackage == null) {
                    throw new IllegalArgumentException("Transform Function package not found");
                }
            }

            SinkConfigUtils.ExtractedSinkDetails sinkDetails =
                    SinkConfigUtils.validateAndExtractDetails(sinkConfig, connectorFunctionPackage,
                            transformFunctionPackage, workerConfig.getValidateConnectorConfig());

            return SinkConfigUtils.convert(sinkConfig, sinkDetails);
        } finally {
            if (shouldCloseFunctionPackage && connectorFunctionPackage instanceof AutoCloseable) {
                try {
                    ((AutoCloseable) connectorFunctionPackage).close();
                } catch (Exception e) {
                    log.error("Failed to connector function file", e);
                }
            }
            if (shouldCloseTransformFunctionPackage && transformFunctionPackage instanceof AutoCloseable) {
                try {
                    ((AutoCloseable) transformFunctionPackage).close();
                } catch (Exception e) {
                    log.error("Failed to close transform function file", e);
                }
            }
        }
    }
}
