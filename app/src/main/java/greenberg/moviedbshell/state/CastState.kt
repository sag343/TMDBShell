package greenberg.moviedbshell.state

import android.os.Parcelable
import com.airbnb.mvrx.MvRxState
import greenberg.moviedbshell.models.ui.CastMemberItem
import kotlinx.android.parcel.Parcelize

data class CastState(
    val castMembers: List<CastMemberItem> = emptyList()
) : MvRxState {
    constructor(args: CastStateArgs) : this(castMembers = args.castMembers)
}

@Parcelize
data class CastStateArgs(
    val castMembers: List<CastMemberItem>
) : Parcelable
