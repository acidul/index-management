/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.indexmanagement.adminpanel.notification.filter

import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.apache.logging.log4j.LogManager
import org.opensearch.OpenSearchSecurityException
import org.opensearch.OpenSearchStatusException
import org.opensearch.action.ActionListener
import org.opensearch.action.ActionRequest
import org.opensearch.action.ActionResponse
import org.opensearch.action.DocWriteResponse
import org.opensearch.action.admin.indices.forcemerge.ForceMergeRequest
import org.opensearch.action.admin.indices.forcemerge.ForceMergeResponse
import org.opensearch.action.admin.indices.open.OpenIndexRequest
import org.opensearch.action.admin.indices.open.OpenIndexResponse
import org.opensearch.action.admin.indices.shrink.ResizeRequest
import org.opensearch.action.admin.indices.shrink.ResizeResponse
import org.opensearch.action.delete.DeleteResponse
import org.opensearch.action.support.ActiveShardsObserver
import org.opensearch.action.support.WriteRequest
import org.opensearch.client.Client
import org.opensearch.cluster.metadata.IndexNameExpressionResolver
import org.opensearch.cluster.service.ClusterService
import org.opensearch.common.unit.TimeValue
import org.opensearch.commons.notifications.model.EventSource
import org.opensearch.commons.notifications.model.SeverityType
import org.opensearch.index.reindex.BulkByScrollResponse
import org.opensearch.indexmanagement.adminpanel.notification.action.delete.DeleteLRONConfigAction
import org.opensearch.indexmanagement.adminpanel.notification.action.delete.DeleteLRONConfigRequest
import org.opensearch.indexmanagement.adminpanel.notification.action.get.GetLRONConfigsAction
import org.opensearch.indexmanagement.adminpanel.notification.action.get.GetLRONConfigsRequest
import org.opensearch.indexmanagement.adminpanel.notification.action.get.GetLRONConfigsResponse
import org.opensearch.indexmanagement.adminpanel.notification.filter.parser.ForceMergeRespParser
import org.opensearch.indexmanagement.adminpanel.notification.filter.parser.OpenRespParser
import org.opensearch.indexmanagement.adminpanel.notification.filter.parser.ReindexRespParser
import org.opensearch.indexmanagement.adminpanel.notification.filter.parser.ResizeRespParser
import org.opensearch.indexmanagement.adminpanel.notification.util.DEFAULT_LRON_CONFIG_SORT_FIELD
import org.opensearch.indexmanagement.adminpanel.notification.util.getDocID
import org.opensearch.indexmanagement.common.model.rest.DEFAULT_PAGINATION_SIZE
import org.opensearch.indexmanagement.common.model.rest.SORT_ORDER_DESC
import org.opensearch.indexmanagement.common.model.rest.SearchParams
import org.opensearch.indexmanagement.util.OpenForTesting
import org.opensearch.tasks.Task
import org.opensearch.tasks.TaskId
import java.util.function.Consumer

@Suppress("LongParameterList", "MaxLineLength")
@OpenForTesting
class NotificationActionListener<Request : ActionRequest, Response : ActionResponse>(
    val delegate: ActionListener<Response>,
    val client: Client,
    val clusterService: ClusterService,
    val action: String,
    val task: Task,
    val activeShardsObserver: ActiveShardsObserver,
    val request: Request,
    val indexNameExpressionResolver: IndexNameExpressionResolver
) : ActionListener<Response>,
    CoroutineScope by CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineName("NotificationActionListener")) {

    private val logger = LogManager.getLogger(NotificationActionListener::class.java)

    override fun onResponse(response: Response) {
        try {
            delegate.onResponse(response)
            parseAndSendNotification(response)
        } catch (e: Exception) {
            onFailure(e)
        }
    }

    override fun onFailure(e: Exception) {
        try {
            delegate.onFailure(e)
        } finally {
            notify(action, "$action execute failed with error: ${e.message}")
        }
    }

    fun parseAndSendNotification(response: Response) {
        try {
            val callback = object : Consumer<String> {
                override fun accept(defaultMessage: String) {
                    notify(action, defaultMessage)
                }
            }
            when (response) {
                is ResizeResponse -> ResizeRespParser(
                    activeShardsObserver,
                    request as ResizeRequest
                ).parseAndSendNotification(response, callback)

                is BulkByScrollResponse -> ReindexRespParser(task).parseAndSendNotification(response, callback)
                is OpenIndexResponse -> OpenRespParser(
                    activeShardsObserver,
                    request as OpenIndexRequest,
                    indexNameExpressionResolver,
                    clusterService
                ).parseAndSendNotification(response, callback)

                is ForceMergeResponse -> ForceMergeRespParser(request as ForceMergeRequest).parseAndSendNotification(
                    response,
                    callback
                )

                else -> {
                    logger.debug("Action {} is not supported for notification, skip!", action)
                }
            }
        } catch (e: Exception) {
            logger.info("Sending out notification for action:{} failed", action, e)
        }
    }

    fun notify(
        action: String,
        defaultMessage: String
    ) {
        val taskId = TaskId(clusterService.localNode().id, task.id)
        val ids = arrayOf<String>(getDocID(), getDocID(taskId = taskId), getDocID(actionName = action))
        val queryString = "_id:(${ids.map { escapeQueryString(it) }.joinToString(" OR ")})"
        val searchParam = SearchParams(
            DEFAULT_PAGINATION_SIZE, 0, DEFAULT_LRON_CONFIG_SORT_FIELD, SORT_ORDER_DESC, queryString
        )

        client.execute(
            GetLRONConfigsAction.INSTANCE,
            GetLRONConfigsRequest(searchParam),
            object : ActionListener<GetLRONConfigsResponse> {
                override fun onResponse(lronConfigsResponse: GetLRONConfigsResponse) {
                    if (0 == lronConfigsResponse.totalNumber) {
                        logger.info(
                            "No notification channel configured for task: {} action: {}",
                            taskId.toString(),
                            action
                        )
                        return
                    }
                    lronConfigsResponse.lronConfigResponses.first().let {
                        val title = buildNotificationTitle()
                        val eventSource = EventSource(title, taskId.toString(), SeverityType.INFO)
                        launch {
                            val config = it.lronConfig
                            if (config.enabled == true) {
                                it.lronConfig.channels?.forEach {
                                    try {
                                        it.sendNotification(
                                            client,
                                            eventSource,
                                            defaultMessage,
                                            config.user,
                                        )
                                    } catch (osse: OpenSearchStatusException) {
                                        logger.warn("Sending notification failed, restStatus {}", osse.status(), osse)
                                    } catch (e: Exception) {
                                        logger.error("Sending notification failed", e)
                                    }
                                }
                            }

                            // remove one time configuration no matter it is enabled or not
                            if (config.taskId != null) {
                                client.execute(
                                    DeleteLRONConfigAction.INSTANCE,
                                    DeleteLRONConfigRequest(
                                        getDocID(taskId = taskId),
                                        WriteRequest.RefreshPolicy.IMMEDIATE
                                    ),
                                    object : ActionListener<DeleteResponse> {
                                        override fun onResponse(response: DeleteResponse) {
                                            if (response.result == DocWriteResponse.Result.DELETED ||
                                                response.result == DocWriteResponse.Result.NOT_FOUND
                                            ) {
                                                logger.info(
                                                    "One time configuration for task:{} has been removed",
                                                    taskId
                                                )
                                            }
                                        }

                                        override fun onFailure(e: Exception) {
                                            logger.warn("remove one time configuration failed", e)
                                        }
                                    }
                                )
                            }
                        }
                    }
                }

                override fun onFailure(e: Exception) {
                    if (e is OpenSearchSecurityException) {
                        // ignore
                    } else {
                        logger.error("Can't get notification channel config for action {}", action, e)
                    }
                }
            }
        )
    }

    fun escapeQueryString(query: String): String {
        return query.replace("/", "\\/")
            .replace(":", "\\:")
    }

    private fun buildNotificationTitle() = "Index Management - Index Operation Complete Notification"

    public companion object {
        val MAX_WAIT_TIME: TimeValue = TimeValue.timeValueHours(12)
        const val COMPLETED = "has completed."
        const val COMPLETED_WITH_ERROR = "has completed with errors. Error details:"
        val COMPLETED_WITH_TIMEOUT = "has timeout within ${MAX_WAIT_TIME.toHumanReadableString(0)}."
    }
}