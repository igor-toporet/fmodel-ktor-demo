package com.fraktalio

import arrow.continuations.SuspendApp
import arrow.continuations.ktor.server
import arrow.fx.coroutines.resourceScope
import com.fraktalio.adapter.extension.pooledConnectionFactory
import com.fraktalio.adapter.persistence.*
import com.fraktalio.application.Aggregate
import com.fraktalio.application.aggregate
import com.fraktalio.application.materializedView
import com.fraktalio.domain.*
import com.fraktalio.plugins.configureMonitoring
import com.fraktalio.plugins.configureSerialization
import com.fraktalio.plugins.configureTracing
import com.fraktalio.plugins.meterRegistry
import com.fraktalio.routes.homeRouting
import com.fraktalio.routes.restaurantRouting
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.util.logging.*
import io.r2dbc.spi.ConnectionFactory
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch

/**
 * Simple logger
 */
internal val LOGGER = KtorSimpleLogger("com.fraktalio")

/**
 * Main entry point of the application
 * Arrow [SuspendApp] is used to gracefully handle termination of the application
 */
fun main(): Unit = SuspendApp {
    resourceScope {
        val httpEnv = Env.Http()
        val meterRegistry = meterRegistry()
        val connectionFactory: ConnectionFactory = pooledConnectionFactory(Env.R2DBCDataSource())
        // ### Command Side - Event Sourcing ###
        val eventStore = EventStore(connectionFactory).apply { initSchema() }
        val aggregateEventRepository = AggregateEventRepositoryImpl(eventStore)
        val aggregate = aggregate(
            orderDecider(),
            restaurantDecider(),
            orderSaga(),
            restaurantSaga(),
            aggregateEventRepository
        )
        // ### Query Side - Event Streaming & Materialized View ###
        val eventStream = EventStream(connectionFactory).apply { initSchema() }
        val restaurantRepository = RestaurantRepository(connectionFactory).apply { initSchema() }
        val orderRepository = OrderRepository(connectionFactory).apply { initSchema() }
        val materializedViewStateRepository =
            MaterializedViewStateRepositoryImpl(restaurantRepository, orderRepository)

        @Suppress("UNUSED_VARIABLE")
        val materializedView = materializedView(
            restaurantView(),
            orderView(),
            materializedViewStateRepository
        ).also { launch { eventStream.registerMaterializedViewAndStartPooling("view", it) } }

        server(CIO, host = httpEnv.host, port = httpEnv.port) {
            configureSerialization()
            configureMonitoring(meterRegistry)
            configureTracing()

            module(aggregate, restaurantRepository, orderRepository)
        }
        awaitCancellation()
    }
}

fun Application.module(
    aggregate: Aggregate,
    restaurantRepository: RestaurantRepository,
    orderRepository: OrderRepository
) {
    homeRouting()
    restaurantRouting(aggregate, restaurantRepository, orderRepository)
}
