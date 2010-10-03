/* Copyright 2010 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.grails.activiti

import org.activiti.engine.task.Task
import org.codehaus.groovy.grails.commons.ApplicationHolder as AH
import grails.util.GrailsNameUtils
import org.codehaus.groovy.grails.commons.ConfigurationHolder as CH

/**
 *
 * @author <a href='mailto:limcheekin@vobject.com'>Lim Chee Kin</a>
 *
 * @since 5.0.beta2
 */
class ActivitiService {
	
	def runtimeService
	def taskService
	def identityService
	
	def startProcess(Map params) {
		runtimeService.startProcessInstanceByKey(params.controller, params)
	}		
	
	private findTasks(String methodName, String username, int firstResult, int maxResults, Map orderBy) {
		def taskQuery = taskService.createTaskQuery()			
		if (methodName) {
			taskQuery."${methodName}"(username)
		}
		if (orderBy) {
			orderBy.each { k, v ->
				taskQuery."orderBy${GrailsNameUtils.getClassNameRepresentation(k == 'id'?'taskId':k)}"()."${v}"()
			}
		}			
		taskQuery.listPage(firstResult, maxResults)
	}
	
	private Long getTasksCount(String methodName, String username) {
		def taskQuery = taskService.createTaskQuery()
		if (methodName) {
			taskQuery."${methodName}"(username)
		}
		taskQuery.count()
	} 
	
	def getAssignedTasksCount(String username) {
		getTasksCount("assignee", username)
	}
	
	def getUnassignedTasksCount(String username) {
		getTasksCount("candidateUser", username)
	}
	
	def getAllTasksCount() {
		getTasksCount(null, null)
	}
	
	def findAssignedTasks(Map params) {
		def orderBy = CH.config.activiti.assignedTasksOrderBy?:[:]
		if (params.sort) {
			orderBy << ["${params.sort}":params.order]
		}
		findTasks("assignee", params.username, getOffset(params.offset), params.max, orderBy)
	}
	
	def findUnassignedTasks(Map params) {
		def orderBy = CH.config.activiti.unassignedTasksOrderBy?:[:]		
		if (params.sort) {
			orderBy << ["${params.sort}":params.order]
		}
		findTasks("candidateUser", params.username, getOffset(params.offset), params.max, orderBy)
	}		
	
	def findAllTasks(Map params) {
		def orderBy = CH.config.activiti.allTasksOrderBy?:[:]		
		if (params.sort) {
			orderBy << ["${params.sort}":params.order]
		}
		findTasks(null, null, getOffset(params.offset), params.max, orderBy)
	}
	
	private getOffset(def offset) {
		return offset?Integer.parseInt(offset):0
	}										  			  				
	
	def deleteTask(String taskId, String domainClassName = null) {
		String id = deleteDomainObject(taskId, domainClassName)
		taskService.deleteTask(taskId)
		return id
	}			  
	
	private deleteDomainObject(String taskId, String domainClassName) {
		Task task = taskService.createTaskQuery().taskId(taskId).singleResult()
		String id = getDomainObjectId(task)
		if (id) {
			def domainClass = AH.getApplication().classLoader.loadClass(domainClassName?:getDomainClassName(task))
			domainClass.get(Long.valueOf(id))?.delete(flush: true)
		}
		return id
	}
	
	private getDomainObjectId(Task task) {
		runtimeService.getVariable(task.executionId, "id")
	}
	
	private getDomainClassName(Task task) {
		runtimeService.getVariable(task.executionId, "domainClassName")
	}	   
	
	Task getAssignedTask(String username, String processInstanceId) {
		getTask("assignee", username, processInstanceId)
	}
	
	Task getUnassignedTask(String username, String processInstanceId) {
		getTask("candidateUser", username, processInstanceId)
	}	
	
	private getTask(String methodName, String username, String processInstanceId) {
		taskService.createTaskQuery()
		.processInstanceId(processInstanceId)
		."${methodName}"(username)
		.singleResult()
	}
	
	def claimTask(String taskId, String username) {
		taskService.claim(taskId, username)
	}		
	
	def completeTask(String taskId, Map params) {
		String executionId = taskService.createTaskQuery().taskId(taskId).singleResult().executionId
		setIdAndDomainClassName(executionId, params)
		runtimeService.setVariable(executionId, "uri", null)
		taskService.complete(taskId, params)
	}
	
	private setIdAndDomainClassName(String executionId, Map params) {
		if (params.id) {
			runtimeService.setVariable(executionId, "id", params.id)
			runtimeService.setVariable(executionId, "domainClassName", params.domainClassName)
		}
	}
	
	def setTaskFormUri(Map params) {
		String executionId = taskService.createTaskQuery().taskId(params.taskId).singleResult().executionId
		setIdAndDomainClassName(executionId, params)
		if (params.controller && params.action && params.id) {
			runtimeService.setVariable(executionId, "uri", "/${params.controller}/${params.action}/${params.id}")
		}
	}						
	
	String getTaskFormUri(String taskId) {
		Task task = taskService.createTaskQuery().taskId(taskId).singleResult()
		String taskFormUri = runtimeService.getVariable(task.executionId, "uri")
		if (!taskFormUri) {
			def id = getDomainObjectId(task)?:""
			taskFormUri = "${task.formResourceKey}/${id}"
		}
		if (taskFormUri) {
			taskFormUri += "?taskId=${taskId}"
		}
		return taskFormUri
	}
	
	def setAssignee(String taskId, String username) {
		taskService.setAssignee(taskId, username)
	}
	
	def setPriority(String taskId, int priority) {
		taskService.setPriority(taskId, priority)
	}
	
	def getCandidateUserIds(String taskId) {
		def identityLinks = taskService.getIdentityLinksForTask(taskId)
		def userIds = []
		identityLinks.each { identityLink -> 
			if (identityLink.groupId) {
			    userIds << identityService.createUserQuery()
					           .memberOfGroup(identityLink.groupId)
								     .orderById().asc().list().collect { it.id }
			} else {
			    userIds << identityLink.userId
			}
		}  		
		return userIds.flatten().unique()
	}
}