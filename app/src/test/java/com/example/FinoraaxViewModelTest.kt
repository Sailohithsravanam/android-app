package com.example

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.*
import com.example.viewmodel.FinoraaxViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class FinoraaxViewModelTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: FinoraaxDao
    private lateinit var repository: FinoraaxRepository
    private lateinit var viewModel: FinoraaxViewModel
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun createDb() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor { it.run() }
            .setTransactionExecutor { it.run() }
            .build()
        dao = db.finoraaxDao()
        repository = FinoraaxRepository(dao)
        
        Dispatchers.setMain(testDispatcher)
        
        viewModel = FinoraaxViewModel(context.applicationContext as Application, repository)
    }

    @After
    @Throws(IOException::class)
    fun closeDb() {
        db.close()
        Dispatchers.resetMain()
    }

    private fun TestScope.collectStates() {
        backgroundScope.launch { viewModel.userState.collect {} }
        backgroundScope.launch { viewModel.transactionsState.collect {} }
        backgroundScope.launch { viewModel.budgetsState.collect {} }
        backgroundScope.launch { viewModel.notificationsState.collect {} }
        backgroundScope.launch { viewModel.subHealthScoreState.collect {} }
        backgroundScope.launch { viewModel.subscriptionsState.collect {} }
    }

    private fun yieldAndSleep() {
        Thread.sleep(100)
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        testDispatcher.scheduler.advanceUntilIdle()
    }

    @Test
    fun testOnboardingCompletion() = runTest(testDispatcher) {
        collectStates()
        yieldAndSleep()

        // Initially no user exists or defaults to null
        assertNull(viewModel.userState.value)

        // Complete onboarding step 'privacy'
        viewModel.completeOnboardingStep("privacy")
        yieldAndSleep()

        val user = viewModel.userState.value
        assertNotNull(user)
        assertTrue(user!!.privacyOnboarded)
        assertFalse(user.leakDetectorOnboarded)
    }

    @Test
    fun testProfileRegistration() = runTest(testDispatcher) {
        collectStates()
        yieldAndSleep()

        // Complete full registration
        viewModel.completeProfileRegistration("Test User", "test@example.com", "1234")
        yieldAndSleep()

        val user = viewModel.userState.value
        assertNotNull(user)
        assertEquals("Test User", user!!.name)
        assertEquals("test@example.com", user.email)
        assertTrue(user.biometricEnabled)
        assertTrue(user.privacyOnboarded)
        assertTrue(user.leakDetectorOnboarded)
        assertTrue(user.advisorOnboarded)
        assertNotNull(user.sessionToken)
    }

    @Test
    fun testTransactionBudgetUpdateAndOverspendAlert() = runTest(testDispatcher) {
        collectStates()
        yieldAndSleep()

        // Setup initial user
        viewModel.completeProfileRegistration("Test User", "test@example.com", "1234")
        yieldAndSleep()

        // Insert a Category budget
        val budget = BudgetEntity(category = "Groceries", limitAmount = 100.0, spentAmount = 0.0, monthYear = "2026-06")
        val totalBudget = BudgetEntity(category = "All", limitAmount = 500.0, spentAmount = 0.0, monthYear = "2026-06")
        dao.insertBudget(budget)
        dao.insertBudget(totalBudget)
        yieldAndSleep()

        // Log transaction within limit
        viewModel.addTransaction(
            type = "EXPENSE",
            category = "Groceries",
            amount = 40.0,
            date = "2026-06-18",
            note = "Bought snacks"
        )
        yieldAndSleep()

        // Fetch transactions & budgets
        val txs = viewModel.transactionsState.value
        assertEquals(1, txs.size)
        assertEquals(40.0, txs[0].amount, 0.0)

        // Verify budget spent amount updated
        val updatedBudgets = viewModel.budgetsState.value
        val groceriesBudget = updatedBudgets.find { it.category == "Groceries" }
        assertNotNull(groceriesBudget)
        assertEquals(40.0, groceriesBudget!!.spentAmount, 0.0)

        // Log transaction exceeding limit
        viewModel.addTransaction(
            type = "EXPENSE",
            category = "Groceries",
            amount = 80.0, // total spent = 120.0, exceeds limit 100.0
            date = "2026-06-18",
            note = "Weekly groceries"
        )
        yieldAndSleep()

        // Verify budget status overspent and alert notification triggered
        val notifications = viewModel.notificationsState.value
        assertTrue(notifications.any { it.type == "BUDGET_ALERT" && it.title.contains("Budget Overspend") })
    }

    @Test
    fun testHighInflowNotification() = runTest(testDispatcher) {
        collectStates()
        yieldAndSleep()

        // Setup initial user
        viewModel.completeProfileRegistration("Test User", "test@example.com", "1234")
        yieldAndSleep()

        // Log large income transaction
        viewModel.addTransaction(
            type = "INCOME",
            category = "Salary",
            amount = 5000.0, // Large cash flow > 1000
            date = "2026-06-18",
            note = "Monthly Paycheck"
        )
        yieldAndSleep()

        // Verify high cash flow notification generated
        val notifications = viewModel.notificationsState.value
        assertTrue(notifications.any { it.type == "FRAUD_ALERT" && it.title.contains("High Cash Flow") })
    }

    @Test
    fun testSubscriptionHealthScoreCalculation() = runTest(testDispatcher) {
        collectStates()
        yieldAndSleep()

        // Setup initial user
        viewModel.completeProfileRegistration("Test User", "test@example.com", "1234")
        yieldAndSleep()

        // Initial health score should be 100
        assertEquals(100, viewModel.subHealthScoreState.value)

        // Add a forgotten subscription
        viewModel.addSubscription(
            name = "Toxic Gym Membership",
            cost = 55.0,
            cycle = "Monthly",
            nextRenewal = "2026-07-01",
            isForgotten = true,
            leakReason = "Unused gym pass"
        )
        yieldAndSleep()

        // Forgotten subscription impact is 20, health score should be 80
        assertEquals(80, viewModel.subHealthScoreState.value)

        // Cancel the subscription
        val subs = viewModel.subscriptionsState.value
        assertEquals(1, subs.size)
        viewModel.cancelSubscription(subs[0].id)
        yieldAndSleep()

        // Cancelled subscription is not active forgotten, health score returns to 100
        assertEquals(100, viewModel.subHealthScoreState.value)
    }

    @Test
    fun testImportBankStatementText() = runTest(testDispatcher) {
        collectStates()
        yieldAndSleep()

        // Seed user details
        viewModel.completeProfileRegistration("Test User", "test@example.com", "1234")
        yieldAndSleep()

        val csvContent = """
            Grocery store food: 75.50
            Monthly Salary credit: 2000.00
            Unrecognized transaction: 15.00
        """.trimIndent()

        val parsedCount = viewModel.importBankStatementText(csvContent)
        yieldAndSleep()

        assertEquals(3, parsedCount)
        val txs = viewModel.transactionsState.value
        assertEquals(3, txs.size)

        val groceriesTx = txs.find { it.category == "Groceries" }
        assertNotNull(groceriesTx)
        assertEquals(75.50, groceriesTx!!.amount, 0.0)
        assertEquals("EXPENSE", groceriesTx.type)

        val salaryTx = txs.find { it.category == "Salary" }
        assertNotNull(salaryTx)
        assertEquals(2000.00, salaryTx!!.amount, 0.0)
        assertEquals("INCOME", salaryTx.type)
    }
}
