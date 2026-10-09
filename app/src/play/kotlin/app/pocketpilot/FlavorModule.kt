package app.pocketpilot

import app.pocketpilot.core.model.PolicyProfile
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Binds the play distribution's [PolicyProfile]; each flavor source set has its own copy. */
@Module
@InstallIn(SingletonComponent::class)
object FlavorModule {
    @Provides
    fun policyProfile(): PolicyProfile = PolicyProfile.PLAY
}
