/*
 * Copyright 2021 Square Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package app.cash.tempest2

import app.cash.tempest2.internal.LogicalDbFactory
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient
import software.amazon.awssdk.enhanced.dynamodb.extensions.annotations.DynamoDbVersionAttribute
import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import javax.annotation.CheckReturnValue
import kotlin.reflect.KClass

/**
 * A collection of tables that implement the DynamoDB best practice of putting multiple
 * item types into the same storage table. This makes it possible to perform aggregate operations
 * and transactions on those item types.
 */
interface LogicalDb : LogicalTable.Factory {

  /**
   * Retrieves multiple items from multiple tables using their primary keys.
   *
   * This method performs one or more calls to the [DynamoDbClient.batchGetItem] API.
   *
   * A single operation can retrieve up to 16 MB of data, which can contain as many as 100 items.
   * BatchGetItem returns a partial result if the response size limit is exceeded, the table's
   * provisioned throughput is exceeded, or an internal processing failure occurs. If a partial
   * result is returned, this method backs off and retries the `UnprocessedKeys` in the next API
   * call.
   */
  fun batchLoad(
    keys: KeySet,
    consistentReads: Boolean = false
  ): ItemSet

  fun batchLoad(
    keys: Iterable<Any>,
    consistentReads: Boolean = false
  ): ItemSet {
    return batchLoad(KeySet(keys), consistentReads)
  }

  fun batchLoad(
    vararg keys: Any,
    consistentReads: Boolean = false
  ): ItemSet {
    return batchLoad(keys.toList(), consistentReads)
  }

  /**
   * Saves and deletes the objects given using one or more calls to the
   * [DynamoDbClient.batchWriteItem] API. **Callers should always check the returned
   * [BatchWriteResult]** because this method returns normally even if some writes were not
   * performed.
   *
   * This method does not support versioning annotations and behaves like [DynamoDbClient.putItem].
   *
   * A single call to BatchWriteItem can write up to 16 MB of data, which can comprise as many as 25
   * put or delete requests. Individual items to be written can be as large as 400 KB.
   *
   * In order to improve performance with these large-scale operations, this does not behave
   * in the same way as individual PutItem and DeleteItem calls would. For example, you cannot specify
   * conditions on individual put and delete requests, and BatchWriteItem does not return deleted
   * items in the response.
   */
  @CheckReturnValue
  fun batchWrite(
    writeSet: BatchWriteSet
  ): BatchWriteResult

  /**
   * Transactionally loads objects specified by transactionLoadRequest by calling
   * [DynamoDbClient.transactGetItems] API.
   *
   * A transaction cannot contain more than 25 unique items.
   * A transaction cannot contain more than 4 MB of data.
   * No two actions in a transaction can work against the same item in the same table.
   */
  fun transactionLoad(keys: KeySet): ItemSet

  fun transactionLoad(keys: Iterable<Any>): ItemSet {
    return transactionLoad(KeySet(keys))
  }

  fun transactionLoad(vararg keys: Any): ItemSet {
    return transactionLoad(keys.toList())
  }

  /**
   * Transactionally writes objects specified by transactionWriteRequest by calling
   * [DynamoDbClient.transactWriteItems] API.
   *
   * This method supports versioning annotations, but not in conjunction with condition expressions.
   * It throws [software.amazon.awssdk.core.exception.SdkClientException] exception if class of
   * any input object is annotated with [DynamoDbVersionAttribute] and a condition expression is
   * also present.
   *
   * A transaction cannot contain more than 25 unique items, including conditions.
   * A transaction cannot contain more than 4 MB of data.
   * No two actions in a transaction can work against the same item in the same table.
   * For example, you cannot both ConditionCheck and Update the same item in one transaction.
   */
  fun transactionWrite(writeSet: TransactionWriteSet)

  companion object {
    inline operator fun <reified DB : LogicalDb> invoke(
      dynamoDbEnhancedClient: DynamoDbEnhancedClient
    ): DB {
      return create(DB::class, dynamoDbEnhancedClient)
    }

    fun <DB : LogicalDb> create(
      dbType: KClass<DB>,
      dynamoDbEnhancedClient: DynamoDbEnhancedClient
    ): DB {
      return LogicalDbFactory(dynamoDbEnhancedClient).logicalDb(dbType)
    }

    // Overloaded functions for Java callers (Kotlin interface companion objects do not support
    // having @JvmStatic and `@JvmOverloads` at the same time).
    // https://youtrack.jetbrains.com/issue/KT-35716

    @JvmStatic
    fun <DB : LogicalDb> create(
      dbType: Class<DB>,
      dynamoDbEnhancedClient: DynamoDbEnhancedClient
    ) = create(dbType.kotlin, dynamoDbEnhancedClient)
  }

  // Overloaded functions for Java callers (Kotlin interfaces do not support `@JvmOverloads`).

  fun batchLoad(
    keys: Iterable<Any>
  ) = batchLoad(keys, consistentReads = false)
}

/**
 * A collection of views on a DynamoDB table that makes it easy to model heterogeneous items
 * using strongly typed data classes.
 */
interface LogicalTable<RI : Any> :
  View<RI, RI>,
  InlineView.Factory,
  SecondaryIndex.Factory {

  /** [type] must be a key type or item type of one of the views of this table. */
  fun <T : Any> codec(type: KClass<T>): Codec<T, RI>

  interface Factory {
    fun <T : LogicalTable<RI>, RI : Any> logicalTable(
      tableName: String,
      tableType: KClass<T>
    ): T
  }
}

interface InlineView<K : Any, I : Any> : View<K, I>, Scannable<K, I>, Queryable<K, I> {

  interface Factory {
    fun <K : Any, I : Any> inlineView(
      keyType: KClass<K>,
      itemType: KClass<I>
    ): InlineView<K, I>
  }
}

interface SecondaryIndex<K : Any, I : Any> : Scannable<K, I>, Queryable<K, I> {

  interface Factory {
    fun <K : Any, I : Any> secondaryIndex(
      keyType: KClass<K>,
      itemType: KClass<I>
    ): SecondaryIndex<K, I>
  }
}
